package se.caibirch.jobpilot.documents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import se.caibirch.jobpilot.claude.ClaudeResources;
import se.caibirch.jobpilot.claude.ClaudeRunner;
import se.caibirch.jobpilot.claude.ClaudeTask;
import se.caibirch.jobpilot.claude.JsonSchemas;
import se.caibirch.jobpilot.cvdb.CvModel.Entry;
import se.caibirch.jobpilot.cvdb.CvModel.Fact;
import se.caibirch.jobpilot.cvdb.CvModel.Profile;
import se.caibirch.jobpilot.cvdb.CvModel.Skill;
import se.caibirch.jobpilot.cvdb.CvService;
import se.caibirch.jobpilot.documents.DocumentModel.CvContent;
import se.caibirch.jobpilot.documents.DocumentModel.Kind;
import se.caibirch.jobpilot.documents.DocumentModel.LetterContent;
import se.caibirch.jobpilot.documents.DocumentModel.Report;
import se.caibirch.jobpilot.documents.DocumentRenderer.Rendered;
import se.caibirch.jobpilot.jobs.JobRepository;
import se.caibirch.jobpilot.jobs.JobRepository.AdText;
import se.caibirch.jobpilot.ranking.AdFormatter;

/**
 * "Ask Claude" in the Documents page: the user asks for a fine adjustment in their own words and the document is revised, checked,
 * rendered and saved as a new version. Claude can only use facts from the CV database (hard rule 4): the validator checks every
 * revision, a CV is passed through {@link CvAssembler} so database-owned fields stay as they are, and one automatic rewrite fixes
 * rule violations. A revision revokes approval, like any edit.
 */
@Service
public class DocumentChatService {

    /** One earlier turn of the conversation, sent back by the page. */
    public record Turn(String role, String text) {
    }

    public record Result(String reply, boolean changed, boolean approvalRevoked, DocumentRepository.Row document) {
    }

    private static final int MAX_MESSAGE = 2_000;
    private static final int MAX_HISTORY = 8;

    private final ClaudeRunner claude;
    private final CvService cv;
    private final JobRepository jobs;
    private final StyleRuleRepository styleRules;
    private final DocumentValidator validator;
    private final DocumentRenderer renderer;
    private final DocumentService docs;
    private final ObjectMapper mapper;

    public DocumentChatService(ClaudeRunner claude, CvService cv, JobRepository jobs, StyleRuleRepository styleRules,
                               DocumentValidator validator, DocumentRenderer renderer, DocumentService docs, ObjectMapper mapper) {
        this.claude = claude;
        this.cv = cv;
        this.jobs = jobs;
        this.styleRules = styleRules;
        this.validator = validator;
        this.renderer = renderer;
        this.docs = docs;
        this.mapper = mapper;
    }

    /** @param draft the content the user sees now, with any unsaved edits; the stored content when null */
    public Result revise(long documentId, String message, JsonNode draft, List<Turn> history) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Write what you want changed");
        }
        if (message.length() > MAX_MESSAGE) {
            throw new IllegalArgumentException("The request is too long (at most " + MAX_MESSAGE + " characters)");
        }
        DocumentRepository.Row row = docs.get(documentId);
        AdText ad = jobs.adText(row.jobId()).orElseThrow();
        var state = jobs.selectionState(row.jobId()).orElseThrow();
        Selection selection = state.selectionJson() == null ? new Selection(List.of()) : docs.read(state.selectionJson(), Selection.class);
        String language = ad.language() != null ? ad.language() : row.language();
        Profile profile = cv.profile();
        boolean cvDoc = row.kind() == Kind.CV;
        JsonNode baseJson = draft != null ? draft : docs.readTree(row.contentJson());
        Object current = docs.parse(row.kind(), baseJson);

        // Every fact of every entry the user has not excluded for this job: the model may use any of them, and only them.
        List<Entry> entries = cv.entries().stream().filter(e -> !e.hidden() && !state.excluded().contains(e.id())).toList();
        Map<Long, Entry> entryById = new LinkedHashMap<>();
        cv.entries().forEach(e -> entryById.put(e.id(), e));
        List<Selection.SelectedEntry> offered = entries.stream()
                .map(e -> new Selection.SelectedEntry(e.id(), e.facts().stream().map(Fact::id).toList(), "both", "")).toList();
        Set<Long> offeredIds = offered.stream().map(Selection.SelectedEntry::entryId).collect(Collectors.toSet());

        Map<String, List<String>> skills = new TreeMap<>();
        for (Skill s : cv.skills()) {
            skills.computeIfAbsent(s.category() == null ? "other" : s.category(), k -> new ArrayList<>()).add(s.name());
        }
        String kind = cvDoc ? "CV" : "cover letter";
        JsonNode answer = claude.run(ClaudeTask.of("reviseDocument", ClaudeResources.prompt("reviseDocument", Map.ofEntries(
                Map.entry("kind", kind), Map.entry("rules", styleRules.rulesText()), Map.entry("ad", AdFormatter.format(ad, mapper)),
                Map.entry("candidate", DocumentService.candidate(profile)), Map.entry("facts", docs.write(docs.factsFor(offered, entryById))),
                Map.entry("skills", docs.write(skills)), Map.entry("language", language), Map.entry("document", docs.write(current)),
                Map.entry("history", historyText(history)), Map.entry("message", message.strip()))),
                ClaudeResources.schema("reviseDocument"), JsonNode.class));

        String reply = answer.path("reply").asText("").strip();
        JsonNode revised = answer.path("document");
        if (!answer.path("changed").asBoolean(false) || revised.equals(baseJson)) {
            return new Result(reply.isEmpty() ? "Nothing needed to change." : reply, false, false, row);
        }
        List<String> problems = JsonSchemas.validate(cvDoc ? "writeCv" : "writeLetter", revised);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Claude answered in the wrong shape, so nothing was changed. Try asking again. (" + problems.get(0) + ")");
        }

        Object content = docs.parse(row.kind(), revised);
        String gapNote = cvDoc ? ((CvContent) current).gapNote() : null;
        Selection widened = widen(selection, cited(content), entryById, offeredIds, cvDoc);
        if (cvDoc) {
            content = CvAssembler.assemble((CvContent) content, widened, entryById, profile, language, gapNote);
        }
        ValidationContext ctx = context(row, ad, language, widened, entryById, cvDoc);
        Report report = report(cvDoc, content, ctx);
        if (!report.violations().isEmpty()) {
            if (cvDoc) {
                CvContent rewritten = docs.rewrite(Kind.CV, (CvContent) content, report, offered, entryById, language, CvContent.class);
                content = CvAssembler.assemble(rewritten, widened, entryById, profile, language, gapNote);
            } else {
                content = docs.rewrite(Kind.LETTER, (LetterContent) content, report, offered, entryById, language, LetterContent.class);
            }
            widened = widen(widened, cited(content), entryById, offeredIds, cvDoc);
            ctx = context(row, ad, language, widened, entryById, cvDoc);
            report = report(cvDoc, content, ctx);
        }

        Rendered rendered;
        try {
            rendered = cvDoc ? renderer.renderCv((CvContent) content, profile) : renderer.renderLetter((LetterContent) content, profile);
        } catch (RuntimeException e) {
            throw new IllegalStateException("The revised document could not be rendered, so nothing was changed: " + e.getMessage(), e);
        }
        docs.store(row.jobId(), row.kind(), row.language(), content, rendered, report, profile, ad.employer(), row.searchProfileId());
        jobs.saveSelection(row.jobId(), docs.write(widened));
        jobs.revertDocsReady(row.jobId());
        boolean revoked = row.approved();
        String note = report.hasFailures() ? " It has problems to fix before it can be approved." : "";
        return new Result(reply + note, true, revoked, docs.get(documentId));
    }

    private Report report(boolean cvDoc, Object content, ValidationContext ctx) {
        return cvDoc ? validator.validateCv((CvContent) content, ctx) : validator.validateLetter((LetterContent) content, ctx);
    }

    private ValidationContext context(DocumentRepository.Row row, AdText ad, String language, Selection selection,
                                      Map<Long, Entry> entryById, boolean cvDoc) {
        var job = jobs.findRow(row.jobId(), null).orElseThrow();
        boolean asks = job.asksForNewGraduates() || NewGraduatePhrases.appearsIn(ad.description());
        Set<Long> ids = Selection.ids(cvDoc ? selection.forCv() : selection.forLetter());
        return docs.context(language, asks, new ArrayList<>(entryById.values()), ids, DocumentService.foreignEmployers(language, ad.employer()));
    }

    /** The fact ids the document says it rests on. */
    private Set<Long> cited(Object content) {
        Set<Long> ids = new LinkedHashSet<>();
        if (content instanceof LetterContent l) {
            if (l.factIdsUsed() != null) {
                ids.addAll(l.factIdsUsed());
            }
        } else if (content instanceof CvContent c) {
            c.sections().forEach(s -> s.items().forEach(i -> i.bullets().forEach(b -> {
                if (b.factIds() != null) {
                    ids.addAll(b.factIds());
                }
            })));
        }
        return ids;
    }

    /** Entries the revision now draws on join the job's selection, so the validator and a later regeneration agree with the document. */
    private Selection widen(Selection selection, Set<Long> citedFacts, Map<Long, Entry> entryById, Set<Long> allowedEntries, boolean cvDoc) {
        Map<Long, Selection.SelectedEntry> byId = new LinkedHashMap<>();
        selection.entries().forEach(e -> byId.put(e.entryId(), e));
        String use = cvDoc ? "cv" : "letter";
        for (Entry e : entryById.values()) {
            if (!allowedEntries.contains(e.id())) {
                continue; // hidden or excluded for this job: citing it widens nothing, so the validator flags the citation
            }
            List<Long> used = e.facts().stream().map(Fact::id).filter(citedFacts::contains).toList();
            if (used.isEmpty()) {
                continue;
            }
            Selection.SelectedEntry existing = byId.get(e.id());
            if (existing == null) {
                byId.put(e.id(), new Selection.SelectedEntry(e.id(), used, use, "added by a chat request"));
                continue;
            }
            List<Long> facts = new ArrayList<>(existing.factIds());
            used.stream().filter(f -> !facts.contains(f)).forEach(facts::add);
            String merged = existing.use().equals(use) || existing.use().equals("both") ? existing.use() : "both";
            byId.put(e.id(), new Selection.SelectedEntry(e.id(), facts, merged, existing.reason()));
        }
        return new Selection(new ArrayList<>(byId.values()));
    }

    private static String historyText(List<Turn> history) {
        if (history == null || history.isEmpty()) {
            return "(none)";
        }
        List<Turn> recent = history.subList(Math.max(0, history.size() - MAX_HISTORY), history.size());
        return recent.stream().map(t -> ("assistant".equals(t.role()) ? "You: " : "Job seeker: ")
                + (t.text() == null ? "" : t.text().strip())).collect(Collectors.joining("\n"));
    }
}
