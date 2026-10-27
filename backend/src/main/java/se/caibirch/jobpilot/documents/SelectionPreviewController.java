package se.caibirch.jobpilot.documents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import se.caibirch.jobpilot.cvdb.CvModel.Entry;
import se.caibirch.jobpilot.cvdb.CvModel.Fact;
import se.caibirch.jobpilot.cvdb.CvService;
import se.caibirch.jobpilot.jobs.LanguageDetector;
import se.caibirch.jobpilot.search.SearchProfileRepository;

/** "Preview selection for..." (SPEC 12.6): which CV facts would be chosen for a pasted ad. Nothing is stored. */
@RestController
@RequestMapping("/api/cv")
public class SelectionPreviewController {

    public record PreviewRequest(String ad) {
    }

    public record PreviewFact(long id, String text) {
    }

    public record PreviewEntry(long entryId, String title, String kind, String use, String reason, List<PreviewFact> facts) {
    }

    public record Preview(String language, List<PreviewEntry> entries) {
    }

    private final FactSelectionService selector;
    private final CvService cv;
    private final SearchProfileRepository profiles;

    public SelectionPreviewController(FactSelectionService selector, CvService cv, SearchProfileRepository profiles,
                                      ObjectMapper mapper) {
        this.selector = selector;
        this.cv = cv;
        this.profiles = profiles;
    }

    @PostMapping("/preview-selection")
    public Preview preview(@RequestBody PreviewRequest request) {
        if (request.ad() == null || request.ad().isBlank()) {
            throw new IllegalArgumentException("Paste the ad text first");
        }
        long profileId = profiles.latestId().orElseThrow(() ->
                new IllegalArgumentException("Run a search first: the selection uses the newest search profile"));
        var profile = profiles.find(profileId).orElseThrow().profile();
        String language = LanguageDetector.detect(request.ad());
        List<Entry> entries = cv.entries();
        Map<Long, Entry> byId = entries.stream().collect(Collectors.toMap(Entry::id, Function.identity()));
        Selection selection = selector.select(request.ad(), profile, language, entries, Set.of(), Set.of());
        List<PreviewEntry> out = new ArrayList<>();
        for (Selection.SelectedEntry s : selection.entries()) {
            Entry e = byId.get(s.entryId());
            List<PreviewFact> facts = e.facts().stream().filter(f -> s.factIds().contains(f.id()))
                    .map((Fact f) -> new PreviewFact(f.id(), "sv".equals(language) && f.textSv() != null ? f.textSv() : f.textEn() != null ? f.textEn() : f.textSv()))
                    .toList();
            out.add(new PreviewEntry(e.id(), e.titleEn() != null ? e.titleEn() : e.title(), e.kind().name(), s.use(), s.reason(), facts));
        }
        return new Preview(language, out);
    }
}
