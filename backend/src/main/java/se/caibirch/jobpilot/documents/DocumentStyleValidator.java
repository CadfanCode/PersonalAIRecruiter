package se.caibirch.jobpilot.documents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import se.caibirch.jobpilot.documents.DocumentModel.Severity;
import se.caibirch.jobpilot.documents.DocumentModel.Violation;

/**
 * Style rules from CLAUDE.md and SPEC 8.3, applied to every string of a document.
 * FAIL: dashes, new-graduate phrases (unless the ad asks), a letter that opens with "Jag heter" or
 * "My name is", another employer's name from an example letter. WARN: banned AI phrasing.
 */
public final class DocumentStyleValidator {

    /** U+2014 and U+2013 as the spec says, plus the figure dash and horizontal bar, which read the same. */
    static final String DASHES = "‒–—―";

    private static final Pattern DASH = Pattern.compile("[" + DASHES + "]");
    private static final Pattern BAD_OPENING = Pattern.compile("^\\s*(jag heter|my name is)\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final boolean adAsksForNewGraduates;
    private final List<Phrase> banned;
    private final Set<String> foreignEmployers;

    /** @param bannedPhrases enabled phrases of the document's language; " ... " joins two parts that must both occur */
    public DocumentStyleValidator(boolean adAsksForNewGraduates, List<String> bannedPhrases, Set<String> foreignEmployers) {
        this.adAsksForNewGraduates = adAsksForNewGraduates;
        this.banned = bannedPhrases.stream().map(Phrase::of).toList();
        this.foreignEmployers = foreignEmployers;
    }

    private record Phrase(String text, Pattern pattern) {
        static Phrase of(String phrase) {
            String[] parts = phrase.split("\\s*\\.\\.\\.\\s*");
            StringBuilder regex = new StringBuilder("(?<![\\p{L}\\p{N}])");
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    regex.append("[^.!?]*?");
                }
                regex.append(Pattern.quote(parts[i].trim()));
            }
            // phrases like "thrilled" also match "thrilled about"; the trailing boundary is only for whole words
            return new Phrase(phrase, Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
    }

    /** @param strings every text of the document keyed by its location, in reading order */
    public List<Violation> validate(Map<String, String> strings, boolean isLetter) {
        List<Violation> out = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, String> e : strings.entrySet()) {
            String where = e.getKey();
            String text = e.getValue();
            if (text == null || text.isEmpty()) {
                continue;
            }
            Matcher dash = DASH.matcher(text);
            if (dash.find()) {
                out.add(new Violation("dash", Severity.FAIL, where, shown(dash.group()),
                        "Dashes (U+2012 to U+2015) are not allowed; use a comma, colon or full stop"));
            }
            if (!adAsksForNewGraduates) {
                NewGraduatePhrases.find(text).ifPresent(p -> out.add(new Violation("new_graduate", Severity.FAIL, where, p,
                        "Do not call the candidate a new graduate unless the ad asks for one")));
            }
            for (Phrase p : banned) {
                Matcher m = p.pattern().matcher(text);
                if (m.find()) {
                    out.add(new Violation("banned_phrase", Severity.WARN, where, m.group(),
                            "Typical AI phrasing: \"" + p.text() + "\""));
                }
            }
            for (String employer : foreignEmployers) {
                if (text.toLowerCase(Locale.ROOT).contains(employer.toLowerCase(Locale.ROOT))) {
                    out.add(new Violation("foreign_employer", Severity.FAIL, where, employer,
                            "Names an employer from the example letter, not the one applied to"));
                }
            }
            if (isLetter && first && where.startsWith("paragraphs") && BAD_OPENING.matcher(text).find()) {
                out.add(new Violation("bad_opening", Severity.FAIL, where, text.substring(0, Math.min(30, text.length())),
                        "The letter must not open with \"Jag heter\" or \"My name is\""));
            }
            if (where.startsWith("paragraphs")) {
                first = false;
            }
        }
        return out;
    }

    private static String shown(String dash) {
        return dash + " (U+" + String.format("%04X", (int) dash.charAt(0)) + ")";
    }
}
