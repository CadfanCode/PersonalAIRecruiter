package se.caibirch.jobpilot.cvdb;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import se.caibirch.jobpilot.claude.ClaudeResources;
import se.caibirch.jobpilot.claude.ClaudeRunner;
import se.caibirch.jobpilot.claude.ClaudeTask;
import se.caibirch.jobpilot.common.ApiExceptions.NotFoundException;
import se.caibirch.jobpilot.cvdb.CvModel.*;

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

    /** Claude's answer for the {@code importCv} task. */
    public record ImportedCv(List<ImportedEntry> entries, List<ImportedSkill> skills) {
    }

    /** An entry in the preview; {@code duplicateOfEntryId} is set when the database already has it. */
    public record PreviewEntry(ImportedEntry entry, Long duplicateOfEntryId) {
    }

    public record Preview(String previewId, List<String> files, List<PreviewEntry> entries,
                          List<ImportedSkill> skills) {
    }

    /** What the user confirmed in the wizard (possibly edited, possibly fewer entries). */
    public record CommitRequest(List<ImportedEntry> entries, List<ImportedSkill> skills) {
    }

    public record CommitResult(List<Long> entryIds, int facts, int skills) {
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
                entries, result.skills());
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
        previews.remove(previewId);
        return new CommitResult(ids, facts, request.skills().size());
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(TTL);
        previews.values().removeIf(s -> s.created().isBefore(cutoff));
    }
}
