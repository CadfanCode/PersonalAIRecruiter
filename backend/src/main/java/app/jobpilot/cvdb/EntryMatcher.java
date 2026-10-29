package app.jobpilot.cvdb;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import app.jobpilot.cvdb.CvModel.Entry;
import app.jobpilot.cvdb.CvModel.Kind;

/**
 * Decides whether two entries describe the same job, course or project. An imported entry that matches is merged into the one the
 * database has (its new facts are added), so a wrong "same" puts facts under the wrong job and a wrong "different" only leaves a
 * second entry the user can merge by hand. That is why this is stricter than it used to be.
 *
 * <p>Same kind, and then:
 * <ul>
 *   <li>both name an organisation: one organisation's words must be contained in the other's ("GIH" in "Gymnastik- och
 *       idrottshögskolan (GIH)"), and <em>also</em> the titles must be alike or the roles must start at the same time. Two roles
 *       at one employer (Undersköterska 2015 to 2018, then Sjuksköterska from 2019) are two entries;</li>
 *   <li>an organisation is missing on either side: the titles must be alike (equal, contained, or sharing about a third of
 *       their words, which covers Swedish and English versions of one title).</li>
 * </ul>
 */
final class EntryMatcher {

    private static final double TITLE_OVERLAP = 0.35;

    private EntryMatcher() {
    }

    /** The parts of an entry that decide whether it is the same as another; built from a stored or an imported entry. */
    record Key(Kind kind, String title, String titleEn, String organisation, String startYm) {
        static Key of(Entry e) {
            return new Key(e.kind(), e.title(), e.titleEn(), e.organisation(), e.startYm());
        }

        static Key of(CvImportService.ImportedEntry e) {
            return new Key(e.kind(), e.title(), e.titleEn(), e.organisation(), e.startYm());
        }
    }

    static Optional<Entry> findMatch(Key key, List<Entry> existing) {
        return existing.stream().filter(e -> same(key, Key.of(e))).findFirst();
    }

    static boolean same(Key a, Key b) {
        if (a.kind() != b.kind()) {
            return false;
        }
        boolean titlesAlike = similarTitle(words(a.title()), words(b.title())) || similarTitle(words(a.title()), words(b.titleEn()))
                || similarTitle(words(a.titleEn()), words(b.title())) || similarTitle(words(a.titleEn()), words(b.titleEn()));
        Set<String> orgA = words(a.organisation());
        Set<String> orgB = words(b.organisation());
        if (!orgA.isEmpty() && !orgB.isEmpty()) {
            return contained(orgA, orgB) && (titlesAlike || sameStart(a.startYm(), b.startYm()));
        }
        return titlesAlike;
    }

    /** "2019" and "2019-03" start in the same period; unknown dates prove nothing. */
    static boolean sameStart(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        return a.startsWith(b) || b.startsWith(a);
    }

    private static boolean similarTitle(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (contained(a, b)) {
            return true;
        }
        Set<String> common = new HashSet<>(a);
        common.retainAll(b);
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return (double) common.size() / all.size() >= TITLE_OVERLAP;
    }

    private static boolean contained(Set<String> a, Set<String> b) {
        return b.containsAll(a) || a.containsAll(b);
    }

    private static Set<String> words(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return new HashSet<>(List.of(AnswerKeys.normalise(text).split(" ")));
    }
}
