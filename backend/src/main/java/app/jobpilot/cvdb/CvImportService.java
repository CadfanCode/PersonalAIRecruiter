package app.jobpilot.cvdb;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.jobpilot.claude.ClaudeResources;
import app.jobpilot.claude.ClaudeRunner;
import app.jobpilot.claude.ClaudeTask;
import app.jobpilot.common.ApiExceptions.NotFoundException;
import app.jobpilot.cvdb.CvModel.*;

/**
 * Import wizard back end: upload, Claude splits the text into entries and facts, the user reviews,
 * and only the commit writes to the database. Previews live in memory for an hour.
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

    /** An entry in the preview; {@code duplicateOfEntryId} is set when the database already has it. */
    public record PreviewEntry(ImportedEntry entry, Long duplicateOfEntryId) {
    }

    public record Preview(String previewId, List<String> files, List<PreviewEntry> entries,
                          List<ImportedSkill> skills, ImportedProfile profile) {
    }

    /** What the user confirmed in the wizard (possibly edited, possibly fewer entries). */
    public record CommitRequest(List<ImportedEntry> entries, List<ImportedSkill> skills, ImportedProfile profile) {
    }

    public record CommitResult(List<Long> entryIds, int facts, int skills, boolean profileFilled) {
    }

    public record UploadedFile(String name, byte[] bytes) {
    }

    private static final Duration TTL = Duration.ofHours(1);

    private final DocumentTextExtractor extractor;
    private final ClaudeRunner claude;
    private final CvService service;
    private final Map<String, Stored> previews = new ConcurrentHashMap<>();

    private record Stored(Instant created, Preview preview) {
    }

    public CvImportService(DocumentTextExtractor extractor, ClaudeRunner claude, CvService service) {
        this.extractor = extractor;
        this.claude = claude;
        this.service = service;
    }

    public Preview preview(List<UploadedFile> files) {
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
        String tags = service.tags().stream().map(Tag::name).collect(Collectors.joining(", "));
        ImportedCv result = claude.run(ClaudeTask.longRunning("importCv",
                ClaudeResources.prompt("importCv", Map.of("existingTags", tags.isEmpty() ? "(none yet)" : tags,
                        "text", text.toString().strip())),
                ClaudeResources.schema("importCv"), ImportedCv.class));

        List<Entry> existing = service.entries();
        List<PreviewEntry> entries = result.entries().stream()
                .map(e -> new PreviewEntry(e, EntryMatcher.findMatch(e.kind(), e.title(), e.organisation(), existing)
                        .map(Entry::id).orElse(null))).toList();
        Preview preview = new Preview(UUID.randomUUID().toString(), files.stream().map(UploadedFile::name).toList(),
                entries, result.skills(), result.profile());
        evictExpired();
        previews.put(preview.previewId(), new Stored(Instant.now(), preview));
        return preview;
    }

    @Transactional
    public CommitResult commit(String previewId, CommitRequest request) {
        if (previews.get(previewId) == null) {
            throw new NotFoundException("Import preview " + previewId + " has expired or does not exist");
        }
        int facts = 0;
        List<Long> ids = new java.util.ArrayList<>();
        int order = service.entries().size();
        for (ImportedEntry e : request.entries()) {
            EntryInput in = new EntryInput(e.kind(), e.title(), e.titleEn(), e.organisation(), e.location(),
                    e.startYm(), e.endYm(), e.ongoing(), null, null, order++, false, e.tags());
            List<FactInput> factInputs = new java.util.ArrayList<>();
            int factOrder = 0;
            for (ImportedFact f : e.facts()) {
                factInputs.add(new FactInput(f.textEn(), f.textSv(), f.evidence(), null, factOrder++, f.tags()));
            }
            ids.add(service.createEntry(in, factInputs).id());
            facts += factInputs.size();
        }
        for (ImportedSkill s : request.skills()) {
            service.addSkillIfAbsent(s.name(), s.category());
        }
        boolean profileFilled = fillProfile(request.profile());
        previews.remove(previewId);
        return new CommitResult(ids, facts, request.skills().size(), profileFilled);
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
        previews.values().removeIf(s -> s.created().isBefore(cutoff));
    }
}
