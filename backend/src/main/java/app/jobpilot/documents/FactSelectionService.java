package app.jobpilot.documents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import app.jobpilot.claude.ClaudeResources;
import app.jobpilot.claude.ClaudeRunner;
import app.jobpilot.claude.ClaudeTask;
import app.jobpilot.cvdb.CvModel.Entry;
import app.jobpilot.cvdb.CvModel.Fact;
import app.jobpilot.cvdb.CvModel.Kind;
import app.jobpilot.documents.Selection.SelectedEntry;
import app.jobpilot.search.SearchProfile;

/**
 * Asks Claude which entries and facts fit the ad, then enforces the rules in Java so a bad answer cannot
 * leak an excluded, hidden or invented entry into a document (SPEC 8.1).
 */
@Service
public class FactSelectionService {

    private final ClaudeRunner claude;
    private final ObjectMapper mapper;

    public FactSelectionService(ClaudeRunner claude, ObjectMapper mapper) {
        this.claude = claude;
        this.mapper = mapper;
    }

    public Selection select(String adText, SearchProfile profile, String language, List<Entry> allEntries,
                            Set<Long> pinned, Set<Long> excluded) {
        List<Entry> usable = allEntries.stream().filter(e -> !e.hidden() && !excluded.contains(e.id())).toList();
        Selection raw = claude.run(ClaudeTask.of("selectFacts",
                ClaudeResources.prompt("selectFacts", Map.of("language", language, "profile", json(profile), "ad", adText,
                        "cv", json(digest(usable)), "pinned", pinned.toString(), "excluded", excluded.toString())),
                ClaudeResources.schema("selectFacts"), Selection.class));
        return enforce(raw, usable, pinned);
    }

    /** Pure and testable: drops anything unusable, then adds pinned and education entries. */
    static Selection enforce(Selection raw, List<Entry> usable, Set<Long> pinned) {
        Map<Long, Entry> byId = new LinkedHashMap<>();
        usable.forEach(e -> byId.put(e.id(), e));
        Map<Long, SelectedEntry> chosen = new LinkedHashMap<>();
        for (SelectedEntry s : raw.entries() == null ? List.<SelectedEntry>of() : raw.entries()) {
            Entry entry = byId.get(s.entryId());
            if (entry == null) {
                continue; // unknown, hidden or excluded
            }
            Set<Long> own = new LinkedHashSet<>();
            entry.facts().forEach(f -> own.add(f.id()));
            List<Long> facts = (s.factIds() == null ? List.<Long>of() : s.factIds()).stream().filter(own::contains).distinct().toList();
            SelectedEntry merged = chosen.get(entry.id());
            if (merged != null) {
                List<Long> union = new ArrayList<>(merged.factIds());
                facts.stream().filter(f -> !union.contains(f)).forEach(union::add);
                chosen.put(entry.id(), new SelectedEntry(entry.id(), union, merged.use(), merged.reason()));
            } else {
                chosen.put(entry.id(), new SelectedEntry(entry.id(), facts, valid(s.use()), s.reason()));
            }
        }
        for (Entry e : usable) {
            boolean forced = pinned.contains(e.id()) || e.kind() == Kind.EDUCATION;
            if (!forced) {
                continue;
            }
            SelectedEntry existing = chosen.get(e.id());
            List<Long> all = e.facts().stream().map(Fact::id).toList();
            if (existing == null) {
                chosen.put(e.id(), new SelectedEntry(e.id(), all, "both",
                        pinned.contains(e.id()) ? "Pinned by the user" : "Education is always included"));
            } else if (pinned.contains(e.id())) {
                chosen.put(e.id(), new SelectedEntry(e.id(), all, "both", "Pinned by the user"));
            } else if (!"both".equals(existing.use()) && !"cv".equals(existing.use())) {
                chosen.put(e.id(), new SelectedEntry(e.id(), existing.factIds(), "both", existing.reason()));
            }
        }
        Selection selection = new Selection(new ArrayList<>(chosen.values()));
        if (selection.forCv().isEmpty()) {
            throw new IllegalStateException("No CV entries were selected; check the CV database and the excluded entries");
        }
        return selection;
    }

    private static String valid(String use) {
        return "cv".equals(use) || "letter".equals(use) ? use : "both";
    }

    /** Full database view for selection: both languages and tags, never the owner's notes. */
    private List<Map<String, Object>> digest(List<Entry> entries) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entryId", e.id());
            m.put("kind", e.kind());
            m.put("title", e.titleEn() != null ? e.titleEn() : e.title());
            m.put("organisation", e.organisation());
            m.put("from", e.startYm());
            m.put("to", e.ongoing() ? "present" : e.endYm());
            m.put("tags", e.tags());
            List<Map<String, Object>> facts = new ArrayList<>();
            for (Fact f : e.facts()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("factId", f.id());
                fm.put("text", f.textEn() != null ? f.textEn() : f.textSv());
                fm.put("tags", f.tags());
                facts.add(fm);
            }
            m.put("facts", facts);
            out.add(m);
        }
        return out;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
