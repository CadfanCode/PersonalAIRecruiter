package app.jobpilot.cvdb;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.jobpilot.claude.ClaudeResources;
import app.jobpilot.claude.ClaudeRunner;
import app.jobpilot.claude.ClaudeTask;
import app.jobpilot.common.ApiExceptions.NotFoundException;
import app.jobpilot.cvdb.CvModel.*;
import app.jobpilot.pipeline.PipelineModel.Status;
import app.jobpilot.pipeline.RunRepository;

/**
 * Import back end. Uploading starts a background run (it takes minutes): the files are read, Claude splits the text into entries and
 * facts, and the result waits as a preview. The user reviews it, and only the commit writes to the database. An entry that is already
 * in the database is not duplicated: its new facts are added to it, blank fields are filled and tags are added; nothing that is
 * there is changed or removed (generated documents cite fact ids). Previews live in memory for an hour.
 */
@Service
public class CvImportService {

    public record ImportedFact(String textEn, String textSv, String evidence, List<String> tags) {
    }

    public record ImportedEntry(Kind kind, String title, String titleEn, String organisation, String location,
                                String startYm, String endYm, boolean ongoing, List<String> tags,
                                List<ImportedFact> facts) {
    }

    public record ImportedSkill(String name, String category) {
    }

    /** The person's own contact details as the documents state them; any field may be null. */
    public record ImportedProfile(String fullName, String headline, String email, String phone, String city, String website,
                                  String linkedin, String github) {
    }

    /** Claude's answer for the {@code importCv} task. */
    public record ImportedCv(List<ImportedEntry> entries, List<ImportedSkill> skills, ImportedProfile profile) {
    }

    /**
     * An entry in the preview. {@code matchEntryId} is the stored entry it describes, if any: confirming it adds {@code newFacts} facts
     * to that entry instead of creating a second one. {@code knownFacts} are the positions (in {@code entry.facts}) of facts the stored
     * entry already has; they are not added.
     */
    public record PreviewEntry(ImportedEntry entry, Long matchEntryId, String matchTitle, int newFacts, List<Integer> knownFacts) {
    }

    public record Preview(String previewId, List<String> files, List<PreviewEntry> entries,
                          List<ImportedSkill> skills, ImportedProfile profile) {
    }

    /**
     * One entry the user confirmed; {@code mergeIntoEntryId} null means "create it as a new entry". {@code skipFacts} are the positions of
     * facts the preview found to be already stored; when it is absent they are worked out again with the cheap matcher only.
     */
    public record CommitEntry(ImportedEntry entry, Long mergeIntoEntryId, List<Integer> skipFacts) {
    }

    /** What the user confirmed in the wizard (possibly edited, possibly fewer entries). */
    public record CommitRequest(List<CommitEntry> entries, List<ImportedSkill> skills, ImportedProfile profile) {
    }

    /** {@code entryIds}: every entry created or enriched. {@code facts}: facts added. */
    public record CommitResult(List<Long> entryIds, int created, int enriched, int facts, int skills, boolean profileFilled) {
    }

    public record UploadedFile(String name, byte[] bytes) {
    }

    /** The state of a reading, as the UI polls it. {@code status}: RUNNING, DONE or FAILED. */
    public record ImportJob(long runId, String status, String message, List<String> files, Preview preview) {
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CvImportService.class);
    private static final Duration TTL = Duration.ofHours(1);

    private final DocumentTextExtractor extractor;
    private final ClaudeRunner claude;
    private final CvService service;
    private final RunRepository runs;
    private final ExecutorService executor;
    private final Clock clock;
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();

    private static final class Job {
        final long runId;
        final Instant created;
        final List<String> files;
        volatile String status = "RUNNING";
        volatile String message = "Reading your CV";
        volatile Preview preview;

        Job(long runId, Instant created, List<String> files) {
            this.runId = runId;
            this.created = created;
            this.files = files;
        }

        ImportJob view() {
            return new ImportJob(runId, status, message, files, preview);
        }
    }

    public CvImportService(DocumentTextExtractor extractor, ClaudeRunner claude, CvService service, RunRepository runs,
                           ExecutorService pipelineExecutor, Clock clock) {
        this.extractor = extractor;
        this.claude = claude;
        this.service = service;
        this.runs = runs;
        this.executor = pipelineExecutor;
        this.clock = clock;
    }

    /** Extracts the text now (so a bad file is refused at once) and reads it in the background. */
    public ImportJob start(List<UploadedFile> files) {
        if (files.isEmpty()) {
            throw new IllegalArgumentException("Upload at least one file");
        }
        StringBuilder text = new StringBuilder();
        for (UploadedFile f : files) {
            String extracted = extractor.extract(f.name(), f.bytes());
            if (extracted.isBlank()) {
                throw new IllegalArgumentException(f.name() + " contains no extractable text (scanned PDF?)");
            }
            text.append("=== File: ").append(f.name()).append(" ===\n").append(extracted).append("\n\n");
        }
        evictExpired();
        long runId = runs.createImportRun(clock.instant());
        Job job = new Job(runId, clock.instant(), files.stream().map(UploadedFile::name).toList());
        jobs.put(runId, job);
        try {
            executor.submit(() -> read(job, text.toString().strip()));
        } catch (RejectedExecutionException e) {
            fail(job, "Too many runs in progress; try again shortly");
        }
        return job.view();
    }

    public ImportJob job(long runId) {
        Job job = jobs.get(runId);
        if (job == null) {
            throw new NotFoundException("That reading has expired or does not exist");
        }
        return job.view();
    }

    /** The newest reading that has not been confirmed or discarded, so the UI can find it again after leaving the page. */
    public java.util.Optional<ImportJob> current() {
        return jobs.values().stream().max(java.util.Comparator.comparingLong(j -> j.runId)).map(Job::view);
    }

    public void discard(long runId) {
        jobs.remove(runId);
    }

    private void read(Job job, String text) {
        try {
            String tags = service.tags().stream().map(Tag::name).collect(Collectors.joining(", "));
            ImportedCv result = claude.run(ClaudeTask.longRunning("importCv",
                    ClaudeResources.prompt("importCv", Map.of("existingTags", tags.isEmpty() ? "(none yet)" : tags, "text", text)),
                    ClaudeResources.schema("importCv"), ImportedCv.class));
            job.preview = buildPreview(job.files, result);
            job.status = "DONE";
            job.message = "Ready to review";
            runs.finish(job.runId, Status.DONE, job.message, clock.instant());
        } catch (RuntimeException e) {
            fail(job, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void fail(Job job, String message) {
        job.status = "FAILED";
        job.message = message;
        runs.finish(job.runId, Status.FAILED, message, clock.instant());
    }

    private Preview buildPreview(List<String> files, ImportedCv result) {
        List<Entry> existing = service.entries();
        List<ImportedEntry> found = mergeWithinUpload(result.entries());
        List<Entry> matches = found.stream().map(e -> EntryMatcher.findMatch(EntryMatcher.Key.of(e), existing).orElse(null)).toList();
        List<List<Integer>> known = new java.util.ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            known.add(matches.get(i) == null ? List.of() : cheapKnown(found.get(i), matches.get(i)));
        }
        askClaudeAboutTheRest(found, matches, known);
        List<PreviewEntry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            Entry m = matches.get(i);
            entries.add(new PreviewEntry(found.get(i), m == null ? null : m.id(), m == null ? null : label(m),
                    m == null ? 0 : found.get(i).facts().size() - known.get(i).size(), known.get(i)));
        }
        return new Preview(UUID.randomUUID().toString(), files, entries, result.skills(), result.profile());
    }

    /** Positions of the imported facts the stored entry already has, as the word matcher can tell. */
    private static List<Integer> cheapKnown(ImportedEntry imported, Entry stored) {
        List<Integer> known = new java.util.ArrayList<>();
        for (int i = 0; i < imported.facts().size(); i++) {
            ImportedFact f = imported.facts().get(i);
            if (FactMatcher.alreadyThere(f.textEn(), f.textSv(), stored.facts())) {
                known.add(i);
            }
        }
        return known;
    }

    /**
     * One batched call for every matched entry whose facts the word matcher could not settle: rewordings and facts written in the
     * other language look new to it. If the call fails the cheap answer stands, so a reading never fails because of this step.
     */
    private void askClaudeAboutTheRest(List<ImportedEntry> found, List<Entry> matches, List<List<Integer>> known) {
        var groups = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i < found.size(); i++) {
            Entry m = matches.get(i);
            if (m == null || m.facts().isEmpty()) {
                continue;
            }
            var undecided = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            for (int j = 0; j < found.get(i).facts().size(); j++) {
                if (!known.get(i).contains(j)) {
                    ImportedFact f = found.get(i).facts().get(j);
                    undecided.addObject().put("idx", j).put("text", f.textEn() != null ? f.textEn() : f.textSv());
                }
            }
            if (undecided.isEmpty()) {
                continue;
            }
            var group = groups.addObject().put("entryId", m.id());
            var stored = group.putArray("stored");
            m.facts().forEach(f -> stored.addObject().put("id", f.id()).put("text", f.textEn() != null ? f.textEn() : f.textSv()));
            group.set("candidates", undecided);
        }
        if (groups.isEmpty()) {
            return;
        }
        try {
            MatchedFacts answer = claude.run(ClaudeTask.of("matchFacts", ClaudeResources.prompt("matchFacts", Map.of("groups", groups.toPrettyString())),
                    ClaudeResources.schema("matchFacts"), MatchedFacts.class));
            for (MatchedGroup g : answer.results()) {
                for (int i = 0; i < found.size(); i++) {
                    Entry m = matches.get(i);
                    if (m != null && m.id() == g.entryId()) {
                        for (int idx : g.alreadyStated()) {
                            if (idx >= 0 && idx < found.get(i).facts().size() && !known.get(i).contains(idx)) {
                                List<Integer> next = new java.util.ArrayList<>(known.get(i));
                                next.add(idx);
                                known.set(i, next);
                            }
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not compare the new facts with the stored ones, using the word match only: {}", e.getMessage());
        }
    }

    /** Claude's answer for {@code matchFacts}: per stored entry, which candidate facts are already stated. */
    record MatchedFacts(List<MatchedGroup> results) {
    }

    record MatchedGroup(long entryId, List<Integer> alreadyStated) {
    }

    private static String label(Entry e) {
        return e.organisation() == null || e.organisation().isBlank() ? e.title() : e.title() + ", " + e.organisation();
    }

    /** Two files describing one job give two imported entries: fold them into one before anything is compared with the database. */
    static List<ImportedEntry> mergeWithinUpload(List<ImportedEntry> imported) {
        List<ImportedEntry> out = new java.util.ArrayList<>();
        for (ImportedEntry e : imported) {
            int at = -1;
            for (int i = 0; i < out.size() && at < 0; i++) {
                if (EntryMatcher.same(EntryMatcher.Key.of(out.get(i)), EntryMatcher.Key.of(e))) {
                    at = i;
                }
            }
            if (at < 0) {
                out.add(e);
            } else {
                out.set(at, fold(out.get(at), e));
            }
        }
        return out;
    }

    private static ImportedEntry fold(ImportedEntry a, ImportedEntry b) {
        List<ImportedFact> facts = new java.util.ArrayList<>(a.facts());
        for (ImportedFact f : b.facts()) {
            boolean there = facts.stream().anyMatch(x -> FactMatcher.similar(f.textEn(), x.textEn()) || FactMatcher.similar(f.textSv(), x.textSv()));
            if (!there) {
                facts.add(f);
            }
        }
        java.util.LinkedHashSet<String> tags = new java.util.LinkedHashSet<>(a.tags() == null ? List.of() : a.tags());
        if (b.tags() != null) {
            tags.addAll(b.tags());
        }
        return new ImportedEntry(a.kind(), a.title(), pick(a.titleEn(), b.titleEn()), pick(a.organisation(), b.organisation()),
                pick(a.location(), b.location()), pick(a.startYm(), b.startYm()), pick(a.endYm(), b.endYm()), a.ongoing() || b.ongoing(),
                List.copyOf(tags), facts);
    }

    private static String pick(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    @Transactional
    public CommitResult commit(String previewId, CommitRequest request) {
        Job job = jobs.values().stream().filter(j -> j.preview != null && j.preview.previewId().equals(previewId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Import preview " + previewId + " has expired or does not exist"));
        int facts = 0;
        int created = 0;
        int enriched = 0;
        List<Long> ids = new java.util.ArrayList<>();
        int order = service.entries().size();
        for (CommitEntry c : request.entries()) {
            ImportedEntry e = c.entry();
            if (c.mergeIntoEntryId() != null) {
                facts += amend(service.entry(c.mergeIntoEntryId()), e, c.skipFacts());
                ids.add(c.mergeIntoEntryId());
                enriched++;
                continue;
            }
            EntryInput in = new EntryInput(e.kind(), e.title(), e.titleEn(), e.organisation(), e.location(),
                    e.startYm(), e.endYm(), e.ongoing(), null, null, order++, false, e.tags());
            List<FactInput> factInputs = new java.util.ArrayList<>();
            int factOrder = 0;
            for (ImportedFact f : e.facts()) {
                factInputs.add(new FactInput(f.textEn(), f.textSv(), f.evidence(), null, factOrder++, f.tags()));
            }
            ids.add(service.createEntry(in, factInputs).id());
            facts += factInputs.size();
            created++;
        }
        for (ImportedSkill s : request.skills()) {
            service.addSkillIfAbsent(s.name(), s.category());
        }
        boolean profileFilled = fillProfile(request.profile());
        jobs.remove(job.runId);
        return new CommitResult(ids, created, enriched, facts, request.skills().size(), profileFilled);
    }

    /**
     * Adds what the stored entry lacks and nothing else: new facts, blank fields and new tags. Existing facts and anything the
     * user wrote are left exactly as they are. Returns how many facts were added.
     */
    private int amend(Entry stored, ImportedEntry imported, List<Integer> skip) {
        java.util.LinkedHashSet<String> tags = new java.util.LinkedHashSet<>(stored.tags());
        if (imported.tags() != null) {
            tags.addAll(imported.tags());
        }
        EntryInput filled = new EntryInput(stored.kind(), stored.title(), blankTo(stored.titleEn(), imported.titleEn()),
                blankTo(stored.organisation(), imported.organisation()), blankTo(stored.location(), imported.location()),
                blankTo(stored.startYm(), imported.startYm()), blankTo(stored.endYm(), imported.endYm()), stored.ongoing(),
                stored.summary(), stored.note(), stored.sortOrder(), stored.hidden(), List.copyOf(tags));
        service.updateEntry(stored.id(), filled);
        // The decision made in the preview (which may include Claude's) stands; without one, the word matcher decides.
        List<Integer> known = skip != null ? skip : cheapKnown(imported, stored);
        int order = stored.facts().size();
        int added = 0;
        for (int i = 0; i < imported.facts().size(); i++) {
            if (known.contains(i)) {
                continue;
            }
            ImportedFact f = imported.facts().get(i);
            service.addFact(stored.id(), new FactInput(f.textEn(), f.textSv(), f.evidence(), null, order++, f.tags()));
            added++;
        }
        return added;
    }

    /**
     * Fills only the blanks of the profile with what the documents say; anything the user already entered stays. Returns true
     * when something was written.
     */
    private boolean fillProfile(ImportedProfile found) {
        if (found == null) {
            return false;
        }
        Profile now = service.profile();
        Profile merged = new Profile(now.id(), blankTo(now.fullName(), found.fullName()), blankTo(now.headline(), found.headline()),
                blankTo(now.email(), found.email()), blankTo(now.phone(), found.phone()), now.phoneInternational(),
                blankTo(now.city(), found.city()), blankTo(now.website(), found.website()), blankTo(now.github(), found.github()),
                blankTo(now.linkedin(), found.linkedin()), now.workAuthorisation(), now.languages());
        if (merged.equals(now)) {
            return false;
        }
        service.saveProfile(merged);
        return true;
    }

    private static String blankTo(String current, String imported) {
        boolean has = current != null && !current.isBlank();
        boolean offered = imported != null && !imported.isBlank();
        return has || !offered ? current : imported.trim();
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(TTL);
        jobs.values().removeIf(j -> j.created.isBefore(cutoff));
    }
}
