package se.caibirch.jobpilot.documents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import se.caibirch.jobpilot.claude.ClaudeResources;
import se.caibirch.jobpilot.claude.ClaudeRunner;
import se.caibirch.jobpilot.claude.ClaudeTask;
import se.caibirch.jobpilot.common.ApiExceptions.ConflictException;
import se.caibirch.jobpilot.common.ApiExceptions.NotFoundException;
import se.caibirch.jobpilot.cvdb.CvModel.Entry;
import se.caibirch.jobpilot.cvdb.CvModel.Fact;
import se.caibirch.jobpilot.cvdb.CvModel.Profile;
import se.caibirch.jobpilot.cvdb.CvModel.Skill;
import se.caibirch.jobpilot.cvdb.CvService;
import se.caibirch.jobpilot.documents.DocumentModel.*;
import se.caibirch.jobpilot.documents.DocumentRenderer.Rendered;
import se.caibirch.jobpilot.jobs.JobModel.Row;
import se.caibirch.jobpilot.jobs.JobRepository;
import se.caibirch.jobpilot.jobs.JobRepository.AdText;
import se.caibirch.jobpilot.jobs.LanguageDetector;
import se.caibirch.jobpilot.pipeline.PipelineModel.Status;
import se.caibirch.jobpilot.pipeline.RunRepository;
import se.caibirch.jobpilot.ranking.AdFormatter;
import se.caibirch.jobpilot.search.SearchProfile;
import se.caibirch.jobpilot.search.SearchProfileRepository;
import se.caibirch.jobpilot.settings.JobPilotProperties;

/**
 * Generates, validates, renders, edits and approves a job's CV and cover letter (SPEC 8). Generation runs as a
 * DOCUMENTS run on the pipeline executor; no database transaction is open during a model call.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final ClaudeRunner claude;
    private final CvService cv;
    private final JobRepository jobs;
    private final RunRepository runs;
    private final SearchProfileRepository profiles;
    private final StyleRuleRepository styleRules;
    private final FactSelectionService selector;
    private final DocumentValidator validator;
    private final DocumentRenderer renderer;
    private final DocumentRepository documents;
    private final ExecutorService executor;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final JobPilotProperties props;

    public DocumentService(ClaudeRunner claude, CvService cv, JobRepository jobs, RunRepository runs,
                           SearchProfileRepository profiles, StyleRuleRepository styleRules, FactSelectionService selector,
                           DocumentValidator validator, DocumentRenderer renderer, DocumentRepository documents,
                           ExecutorService pipelineExecutor, ObjectMapper mapper, Clock clock, JobPilotProperties props) {
        this.claude = claude;
        this.cv = cv;
        this.jobs = jobs;
        this.runs = runs;
        this.profiles = profiles;
        this.styleRules = styleRules;
        this.selector = selector;
        this.validator = validator;
        this.renderer = renderer;
        this.documents = documents;
        this.executor = pipelineExecutor;
        this.mapper = mapper;
        this.clock = clock;
        this.props = props;
    }

    // ---- generation ----

    /** Starts generation for a job and returns the id of the DOCUMENTS run the UI polls. */
    public long start(long jobId, Long sourceRunId) {
        Row row = jobs.findRow(jobId, null).orElseThrow(() -> new NotFoundException("No job " + jobId));
        long searchProfileId = profileFor(jobId, sourceRunId);
        long runId = runs.createDocumentsRun(jobId, searchProfileId, clock.instant());
        try {
            executor.submit(() -> guarded(runId, () -> generate(runId, row.id(), searchProfileId)));
        } catch (RejectedExecutionException e) {
            runs.finish(runId, Status.FAILED, "Too many runs in progress; try again shortly", clock.instant());
            throw new ConflictException("Too many runs in progress; try again shortly");
        }
        return runId;
    }

    public record BatchItem(long jobId, long runId) {
    }

    /**
     * Generates documents for several jobs as ONE task on the pipeline executor (one thread, not N queue slots), job by job.
     * Every job has its own DOCUMENTS run, so one failure is recorded on that job and the rest carry on. A job that already
     * has a generation in progress is not started twice.
     */
    public List<BatchItem> startBatch(List<Long> jobIds, Long sourceRunId) {
        record Plan(long jobId, long profileId) {
        }
        List<Plan> plans = new ArrayList<>();
        for (long jobId : jobIds.stream().distinct().toList()) {
            jobs.findRow(jobId, null).orElseThrow(() -> new NotFoundException("No job " + jobId));
            plans.add(new Plan(jobId, profileFor(jobId, sourceRunId)));
        }
        List<BatchItem> items = new ArrayList<>();
        List<Runnable> work = new ArrayList<>();
        List<Long> created = new ArrayList<>();
        for (Plan plan : plans) {
            var active = runs.activeDocumentsRun(plan.jobId());
            if (active.isPresent()) {
                items.add(new BatchItem(plan.jobId(), active.get()));
                continue;
            }
            long runId = runs.createDocumentsRun(plan.jobId(), plan.profileId(), clock.instant());
            created.add(runId);
            items.add(new BatchItem(plan.jobId(), runId));
            work.add(() -> guarded(runId, () -> generate(runId, plan.jobId(), plan.profileId())));
        }
        if (!work.isEmpty()) {
            try {
                executor.submit(() -> work.forEach(Runnable::run));
            } catch (RejectedExecutionException e) {
                created.forEach(id -> runs.finish(id, Status.FAILED, "Too many runs in progress; try again shortly", clock.instant()));
                throw new ConflictException("Too many runs in progress; try again shortly");
            }
        }
        return items;
    }

    /** The search profile that documents for this job are tailored to: the one of the given run, else the newest run that found it. */
    long profileFor(long jobId, Long sourceRunId) {
        Long found = sourceRunId != null
                ? jobs.profileIdFor(jobId, sourceRunId).orElse(null)
                : jobs.latestSearchProfileId(jobId).orElse(null);
        if (found == null) {
            throw new IllegalArgumentException("No search run found for job " + jobId + "; documents need its search profile");
        }
        return found;
    }

    private void guarded(long runId, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            log.error("Document run {} failed", runId, e);
            runs.finish(runId, Status.FAILED, e.getMessage() == null ? e.toString() : e.getMessage(), clock.instant());
        }
    }

    void generate(long runId, long jobId, long searchProfileId) {
        AdText ad = jobs.adText(jobId).orElseThrow(() -> new NotFoundException("No job " + jobId));
        Row row = jobs.findRow(jobId, null).orElseThrow();
        SearchProfile searchProfile = profiles.find(searchProfileId).orElseThrow().profile();
        String language = ad.language() != null ? ad.language() : LanguageDetector.detect(ad.description());
        boolean asksNewGrad = row.asksForNewGraduates() || NewGraduatePhrases.appearsIn(ad.description());
        Profile profile = cv.profile();
        List<Entry> entries = cv.entries();
        Map<Long, Entry> entryById = new LinkedHashMap<>();
        entries.forEach(e -> entryById.put(e.id(), e));
        var state = jobs.selectionState(jobId).orElseThrow();
        String adText = AdFormatter.format(ad, mapper);

        runs.update(runId, Status.RUNNING, "SELECT", 10, "Choosing the facts that fit this ad");
        Selection selection = selector.select(adText, searchProfile, language, entries, state.pinned(), state.excluded());
        jobs.saveSelection(jobId, write(selection));

        runs.update(runId, Status.RUNNING, "CV", 30, "Writing the CV");
        Set<String> foreign = foreignEmployers(language, ad.employer());
        ValidationContext cvCtx = context(language, asksNewGrad, entries, Selection.ids(selection.forCv()), foreign);
        ValidationContext letterCtx = context(language, asksNewGrad, entries, Selection.ids(selection.forLetter()), foreign);
        String gapNote = GapNote.compute(entries, Selection.ids(selection.forCv()), language, YearMonth.now(clock)).orElse(null);

        CvContent cvDoc = writeCv(adText, language, profile, selection, entryById, entries, cvCtx, gapNote);
        Report cvReport = validator.validateCv(cvDoc, cvCtx);
        if (!cvReport.violations().isEmpty()) {
            runs.progress(runId, 45, "Fixing problems found in the CV");
            cvDoc = CvAssembler.assemble(rewrite(Kind.CV, cvDoc, cvReport, selection.forCv(), entryById, language, CvContent.class),
                    selection, entryById, profile, language, gapNote);
            cvReport = validator.validateCv(cvDoc, cvCtx);
        }

        runs.update(runId, Status.RUNNING, "LETTER", 60, "Writing the cover letter");
        LetterContent letter = writeLetter(adText, ad, language, profile, selection, entryById, letterCtx);
        Report letterReport = validator.validateLetter(letter, letterCtx);
        if (!letterReport.violations().isEmpty()) {
            runs.progress(runId, 80, "Fixing problems found in the letter");
            letter = rewrite(Kind.LETTER, letter, letterReport, selection.forLetter(), entryById, language, LetterContent.class);
            letterReport = validator.validateLetter(letter, letterCtx);
        }

        runs.update(runId, Status.RUNNING, "RENDER", 90, "Rendering the PDFs");
        store(jobId, Kind.CV, language, cvDoc, renderer.renderCv(cvDoc, profile), cvReport, profile, ad.employer(), searchProfileId);
        store(jobId, Kind.LETTER, language, letter, renderer.renderLetter(letter, profile), letterReport, profile, ad.employer(), searchProfileId);

        boolean problems = cvReport.hasFailures() || letterReport.hasFailures();
        runs.finish(runId, Status.DONE, problems ? "Documents ready, but they have problems to fix before approval"
                : "Documents ready for review", clock.instant());
    }

    private CvContent writeCv(String adText, String language, Profile profile, Selection selection, Map<Long, Entry> entryById,
                              List<Entry> entries, ValidationContext ctx, String gapNote) {
        Map<String, List<String>> skills = new TreeMap<>();
        for (Skill s : cv.skills()) {
            skills.computeIfAbsent(s.category() == null ? "other" : s.category(), k -> new ArrayList<>()).add(s.name());
        }
        CvContent written = claude.run(ClaudeTask.of("writeCv",
                ClaudeResources.prompt("writeCv", Map.ofEntries(
                        Map.entry("languageName", languageName(language)), Map.entry("language", language),
                        Map.entry("rules", styleRules.rulesText()), Map.entry("ad", adText),
                        Map.entry("candidate", candidate(profile)),
                        Map.entry("facts", write(factsFor(selection.forCv(), entryById))),
                        Map.entry("skills", write(skills)))),
                ClaudeResources.schema("writeCv"), CvContent.class));
        return CvAssembler.assemble(written, selection, entryById, profile, language, gapNote);
    }

    private LetterContent writeLetter(String adText, AdText ad, String language, Profile profile, Selection selection,
                                      Map<Long, Entry> entryById, ValidationContext ctx) {
        boolean sv = "sv".equals(language);
        String name = profile.fullName() == null ? "" : profile.fullName();
        return claude.run(ClaudeTask.of("writeLetter",
                ClaudeResources.prompt("writeLetter", Map.ofEntries(
                        Map.entry("languageName", languageName(language)), Map.entry("language", language),
                        Map.entry("rules", styleRules.rulesText()), Map.entry("ad", adText),
                        Map.entry("candidate", candidate(profile)),
                        Map.entry("facts", write(factsFor(selection.forLetter(), entryById))),
                        Map.entry("gaps", write(cv.gaps().stream().map(g -> g.text()).toList())),
                        Map.entry("example", ClaudeResources.example(sv ? "letter_sv" : "letter_en")),
                        Map.entry("subjectPrefix", sv ? "Ansökan:" : "Application:"),
                        Map.entry("employer", ad.employer() == null ? "" : ad.employer()),
                        Map.entry("greeting", sv ? "Hej," : "Hello,"),
                        Map.entry("closing", sv ? "Vänliga hälsningar," : "Kind regards,"),
                        Map.entry("signature", name))),
                ClaudeResources.schema("writeLetter"), LetterContent.class));
    }

    /** One automatic rewrite with the violation report (SPEC 8.3). Java-owned fields are restored by the caller. */
    private <T> T rewrite(Kind kind, T document, Report report, List<Selection.SelectedEntry> selected,
                          Map<Long, Entry> entryById, String language, Class<T> type) {
        String schema = kind == Kind.CV ? "writeCv" : "writeLetter";
        return claude.run(ClaudeTask.of("rewriteForViolations",
                ClaudeResources.prompt("rewriteForViolations", Map.of("kind", kind == Kind.CV ? "CV" : "cover letter",
                        "rules", styleRules.rulesText(), "violations", write(report.violations()),
                        "document", write(document), "facts", write(factsFor(selected, entryById)), "language", language)),
                ClaudeResources.schema(schema), type));
    }

    private List<Map<String, Object>> factsFor(List<Selection.SelectedEntry> selected, Map<Long, Entry> entryById) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Selection.SelectedEntry s : selected) {
            Entry e = entryById.get(s.entryId());
            if (e == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entryId", e.id());
            m.put("kind", e.kind());
            m.put("title", e.titleEn() != null ? e.titleEn() : e.title());
            m.put("titleSv", e.title());
            m.put("organisation", e.organisation());
            m.put("location", e.location());
            m.put("from", e.startYm());
            m.put("to", e.ongoing() ? "present" : e.endYm());
            List<Map<String, Object>> facts = new ArrayList<>();
            for (Fact f : e.facts()) {
                if (s.factIds().contains(f.id())) {
                    Map<String, Object> fm = new LinkedHashMap<>();
                    fm.put("id", f.id());
                    fm.put("en", f.textEn());
                    fm.put("sv", f.textSv());
                    facts.add(fm);
                }
            }
            m.put("facts", facts);
            out.add(m);
        }
        return out;
    }

    private ValidationContext context(String language, boolean asksNewGrad, List<Entry> entries, Set<Long> selectedEntryIds,
                                      Set<String> foreignEmployers) {
        Map<Long, ValidationContext.Fact> facts = new HashMap<>();
        for (Entry e : entries) {
            for (Fact f : e.facts()) {
                facts.put(f.id(), new ValidationContext.Fact(f.id(), e.id(), f.textEn(), f.textSv()));
            }
        }
        Set<String> skillNames = cv.skills().stream().map(Skill::name).collect(Collectors.toSet());
        return new ValidationContext(language, asksNewGrad, facts, selectedEntryIds, skillNames,
                styleRules.bannedPhrases(language), foreignEmployers);
    }

    /** Employers named in the example letters, minus the one being applied to. */
    private static Set<String> foreignEmployers(String language, String targetEmployer) {
        Set<String> names = new LinkedHashSet<>(List.of("Knowit", "Ravion"));
        String target = targetEmployer == null ? "" : targetEmployer.toLowerCase();
        names.removeIf(n -> target.contains(n.toLowerCase()));
        return names;
    }

    // ---- storage, edit, approve ----

    private void store(long jobId, Kind kind, String language, Object content, Rendered rendered, Report report,
                       Profile profile, String employer, Long searchProfileId) {
        Report withPages = withPages(report, rendered.pages(), kind);
        Path file = pdfPath(jobId, kind, language, profile, employer);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, rendered.pdf());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file + ": " + e.getMessage(), e);
        }
        documents.upsert(jobId, kind, language, write(content), rendered.html(), file.toString(), write(withPages), searchProfileId);
        jobs.revertDocsReady(jobId); // the upsert cleared the approval

    }

    private Path pdfPath(long jobId, Kind kind, String language, Profile profile, String employer) {
        return props.dataDir().resolve("documents").resolve("job-" + jobId)
                .resolve(FileNames.of(kind, language, profile.fullName(), employer));
    }

    static Report withPages(Report report, int pages, Kind kind) {
        int max = kind == Kind.CV ? 2 : 1;
        List<Violation> all = new ArrayList<>(report.violations().stream().filter(v -> !v.code().equals("too_long")).toList());
        if (pages > max) {
            all.add(new Violation("too_long", Severity.WARN, "document", pages + " pages",
                    "The " + (kind == Kind.CV ? "CV" : "letter") + " should fit on " + max + " page" + (max > 1 ? "s" : "")
                            + " but is " + pages));
        }
        return new Report(all, pages, max);
    }

    public DocumentRepository.Row get(long id) {
        return documents.find(id).orElseThrow(() -> new NotFoundException("No document " + id));
    }

    public List<DocumentRepository.Overview> overview() {
        return documents.overview();
    }

    /** Replaces the content with the user's edit, validates it, re-renders and bumps the version. */
    public DocumentRepository.Row update(long id, JsonNode edited) {
        DocumentRepository.Row row = get(id);
        Profile profile = cv.profile();
        AdText ad = jobs.adText(row.jobId()).orElseThrow();
        Object content = parse(row.kind(), edited);
        Report report;
        Rendered rendered;
        try {
            report = validate(row, content, ad);
            rendered = row.kind() == Kind.CV ? renderer.renderCv((CvContent) content, profile)
                    : renderer.renderLetter((LetterContent) content, profile);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Cannot render the edited document: " + e.getMessage(), e);
        }
        store(row.jobId(), row.kind(), row.language(), content, rendered, report, profile, ad.employer(), row.searchProfileId());
        return get(id);
    }

    /** Re-validates the stored content. Refuses while any FAIL finding remains; never auto-approves. */
    public Report approve(long id) {
        DocumentRepository.Row row = get(id);
        AdText ad = jobs.adText(row.jobId()).orElseThrow();
        Object content = parse(row.kind(), readTree(row.contentJson()));
        Report fresh = withPages(validate(row, content, ad), pagesOf(row), row.kind());
        if (fresh.hasFailures()) {
            documents.setApproved(id, false, write(fresh));
            throw new ApprovalRefused(fresh);
        }
        documents.setApproved(id, true, write(fresh));
        boolean cvOk = documents.find(row.jobId(), Kind.CV).map(DocumentRepository.Row::approved).orElse(false);
        boolean letterOk = documents.find(row.jobId(), Kind.LETTER).map(DocumentRepository.Row::approved).orElse(false);
        if (cvOk && letterOk) {
            jobs.markDocsReady(row.jobId());
        }
        return fresh;
    }

    public static final class ApprovalRefused extends RuntimeException {
        private final transient Report report;

        ApprovalRefused(Report report) {
            super("The document still has problems that must be fixed before it can be approved");
            this.report = report;
        }

        public Report report() {
            return report;
        }
    }

    /** The PDF bytes; re-rendered from the stored content when the file is gone. */
    public byte[] pdf(long id) {
        DocumentRepository.Row row = get(id);
        try {
            if (row.pdfPath() != null && Files.exists(Path.of(row.pdfPath()))) {
                return Files.readAllBytes(Path.of(row.pdfPath()));
            }
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", row.pdfPath(), e.getMessage());
        }
        Object content = parse(row.kind(), readTree(row.contentJson()));
        Profile profile = cv.profile();
        return (row.kind() == Kind.CV ? renderer.renderCv((CvContent) content, profile)
                : renderer.renderLetter((LetterContent) content, profile)).pdf();
    }

    private int pagesOf(DocumentRepository.Row row) {
        try {
            return row.reportJson() == null ? 0 : readTree(row.reportJson()).path("pages").asInt(0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private Report validate(DocumentRepository.Row row, Object content, AdText ad) {
        var state = jobs.selectionState(row.jobId()).orElseThrow();
        Selection selection = state.selectionJson() == null ? new Selection(List.of()) : read(state.selectionJson(), Selection.class);
        Row job = jobs.findRow(row.jobId(), null).orElseThrow();
        boolean asks = job.asksForNewGraduates() || NewGraduatePhrases.appearsIn(ad.description());
        String language = ad.language() != null ? ad.language() : row.language();
        Set<Long> ids = row.kind() == Kind.CV ? Selection.ids(selection.forCv()) : Selection.ids(selection.forLetter());
        ValidationContext ctx = context(language, asks, cv.entries(), ids, foreignEmployers(language, ad.employer()));
        return row.kind() == Kind.CV ? validator.validateCv((CvContent) content, ctx)
                : validator.validateLetter((LetterContent) content, ctx);
    }

    private Object parse(Kind kind, JsonNode json) {
        try {
            return kind == Kind.CV ? mapper.treeToValue(json, CvContent.class) : mapper.treeToValue(json, LetterContent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The document content is not valid: " + e.getOriginalMessage(), e);
        }
    }

    // ---- job selection (pin / exclude) ----

    public void saveSelection(long jobId, Set<Long> pinned, Set<Long> excluded) {
        jobs.findRow(jobId, null).orElseThrow(() -> new NotFoundException("No job " + jobId));
        Set<Long> both = new LinkedHashSet<>(pinned);
        both.retainAll(excluded);
        if (!both.isEmpty()) {
            throw new IllegalArgumentException("An entry cannot be both pinned and excluded: " + both);
        }
        Set<Long> known = cv.entries().stream().map(Entry::id).collect(Collectors.toSet());
        for (Long id : concat(pinned, excluded)) {
            if (!known.contains(id)) {
                throw new IllegalArgumentException("No CV entry " + id);
            }
        }
        jobs.savePins(jobId, pinned, excluded);
    }

    private static Set<Long> concat(Set<Long> a, Set<Long> b) {
        Set<Long> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return out;
    }

    // ---- helpers ----

    private static String languageName(String language) {
        return "sv".equals(language) ? "Swedish" : "English";
    }

    private static String candidate(Profile p) {
        return (p.fullName() == null ? "" : p.fullName()) + (p.headline() == null ? "" : ", " + p.headline());
    }

    private String write(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt stored " + type.getSimpleName(), e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt stored JSON", e);
        }
    }
}
