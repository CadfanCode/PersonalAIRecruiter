package se.caibirch.jobpilot.cvdb;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import se.caibirch.jobpilot.cvdb.CvModel.Entry;
import se.caibirch.jobpilot.cvdb.CvModel.Kind;

/**
 * Decides whether an imported entry is probably one the database already has. Importing the CV a
 * second time must not silently duplicate everything, so this errs toward "same": a false positive
 * only leaves a checkbox unticked, a false negative creates a duplicate.
 *
 * <p>Same kind, and then: when both sides name an organisation, one organisation's words must be
 * contained in the other's ("GIH" in "Gymnastik- och idrottshögskolan (GIH)"). When an organisation
 * is missing on either side, the titles are compared instead (equal, contained, or sharing at least
 * a third of their words, which covers Swedish and English versions of the same title).
 */
final class EntryMatcher {

    private static final double TITLE_OVERLAP = 0.35;

    private EntryMatcher() {
    }

    static Optional<Entry> findMatch(Kind kind, String title, String organisation, List<Entry> existing) {
        return existing.stream().filter(e -> same(kind, title, organisation, e)).findFirst();
    }

    static boolean same(Kind kind, String title, String organisation, Entry e) {
        if (kind != e.kind()) {
            return false;
        }
        Set<String> org = words(organisation);
        Set<String> existingOrg = words(e.organisation());
        if (!org.isEmpty() && !existingOrg.isEmpty()) {
            return contained(org, existingOrg);
        }
        Set<String> t = words(title);
        return similarTitle(t, words(e.title())) || similarTitle(t, words(e.titleEn()));
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
