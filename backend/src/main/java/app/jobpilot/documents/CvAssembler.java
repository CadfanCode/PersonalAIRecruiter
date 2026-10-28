package app.jobpilot.documents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import app.jobpilot.cvdb.CvModel.Entry;
import app.jobpilot.cvdb.CvModel.Kind;
import app.jobpilot.cvdb.CvModel.Language;
import app.jobpilot.cvdb.CvModel.Profile;
import app.jobpilot.documents.DocumentModel.*;

/**
 * Overwrites every factual header field of a written CV from the database: titles, employers, places,
 * dates, the languages line and the gap note. Claude writes only prose, so it cannot invent an employer
 * or a date, and cannot produce an en-dash in a date range.
 */
public final class CvAssembler {

    private CvAssembler() {
    }

    public static CvContent assemble(CvContent written, Selection selection, Map<Long, Entry> entries, Profile profile,
                                     String language, String gapNote) {
        List<Section> sections = new ArrayList<>();
        Set<Long> present = new HashSet<>();
        for (Section s : written.sections()) {
            List<Item> items = new ArrayList<>();
            for (Item i : s.items()) {
                Entry e = i.entryId() > 0 ? entries.get(i.entryId()) : null;
                if (e == null) {
                    items.add(i);
                    continue;
                }
                present.add(e.id());
                items.add(new Item(e.id(), title(e, language), e.organisation(), e.location(),
                        DateLabels.label(e.startYm(), e.endYm(), e.ongoing(), language), i.bullets()));
            }
            sections.add(new Section(s.heading(), s.kind(), items));
        }
        // Education is always listed, with or without bullets.
        List<Item> missing = new ArrayList<>();
        for (Selection.SelectedEntry se : selection.forCv()) {
            Entry e = entries.get(se.entryId());
            if (e != null && e.kind() == Kind.EDUCATION && !present.contains(e.id())) {
                missing.add(new Item(e.id(), title(e, language), e.organisation(), e.location(),
                        DateLabels.label(e.startYm(), e.endYm(), e.ongoing(), language), List.of()));
            }
        }
        if (!missing.isEmpty()) {
            Section education = sections.stream().filter(s -> "EDUCATION".equals(s.kind())).findFirst().orElse(null);
            if (education == null) {
                sections.add(new Section("sv".equals(language) ? "Utbildning" : "Education", "EDUCATION", missing));
            } else {
                int at = sections.indexOf(education);
                List<Item> merged = new ArrayList<>(education.items());
                merged.addAll(missing);
                sections.set(at, new Section(education.heading(), education.kind(), merged));
            }
        }
        return written.withJavaFields(languagesLine(profile.languages(), language), gapNote, sections);
    }

    static String title(Entry e, String language) {
        String en = e.titleEn();
        String sv = e.title();
        if ("en".equals(language)) {
            return en != null && !en.isBlank() ? en : sv;
        }
        return sv != null && !sv.isBlank() ? sv : en;
    }

    /** "Svenska (flytande) och engelska (modersmål)" for a Swedish ad, with the ad's own language first. */
    static String languagesLine(List<Language> languages, String language) {
        if (languages == null || languages.isEmpty()) {
            return null;
        }
        boolean sv = "sv".equals(language);
        String firstName = sv ? "Swedish" : "English";
        List<String> parts = languages.stream()
                .sorted(Comparator.comparing((Language l) -> !firstName.equalsIgnoreCase(l.language())))
                .map(l -> languageName(l.language(), sv) + (l.level() == null || l.level().isBlank() ? ""
                        : " (" + level(l.level(), sv) + ")"))
                .collect(Collectors.toList());
        if (sv && !parts.isEmpty()) {
            parts = new ArrayList<>(parts);
            for (int i = 1; i < parts.size(); i++) {
                parts.set(i, parts.get(i).substring(0, 1).toLowerCase(Locale.ROOT) + parts.get(i).substring(1));
            }
        }
        String and = sv ? " och " : " and ";
        return parts.size() == 1 ? parts.get(0)
                : String.join(", ", parts.subList(0, parts.size() - 1)) + and + parts.get(parts.size() - 1);
    }

    private static String languageName(String name, boolean sv) {
        if (!sv) {
            return name;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "english" -> "Engelska";
            case "swedish" -> "Svenska";
            case "german" -> "Tyska";
            case "french" -> "Franska";
            case "spanish" -> "Spanska";
            default -> name;
        };
    }

    private static String level(String level, boolean sv) {
        return switch (level.toLowerCase(Locale.ROOT)) {
            case "native" -> sv ? "modersmål" : "native";
            case "fluent" -> sv ? "flytande" : "fluent";
            case "good" -> sv ? "goda kunskaper" : "good";
            case "basic" -> sv ? "grundläggande" : "basic";
            default -> level;
        };
    }
}
