package app.jobpilot.cvdb;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.jobpilot.cvdb.CvModel.Fact;

/**
 * Whether a fact from an imported CV is already in the database. Two CVs word one achievement slightly differently, so "the same"
 * means the same words or nearly (at least 80 % of the words shared, or a longer fact that contains every word of a shorter one of at
 * least three words). This is only the cheap first pass: rewordings and facts in the other language are settled by Claude (the
 * {@code matchFacts} task). Existing facts are never changed: a fact that is not matched is added next to them.
 */
final class FactMatcher {

    private static final double SAME = 0.8;

    private FactMatcher() {
    }

    static boolean alreadyThere(String textEn, String textSv, List<Fact> existing) {
        return existing.stream().anyMatch(f -> similar(textEn, f.textEn()) || similar(textSv, f.textSv()));
    }

    static boolean similar(String a, String b) {
        Set<String> x = words(a);
        Set<String> y = words(b);
        if (x.isEmpty() || y.isEmpty()) {
            return false;
        }
        Set<String> common = new HashSet<>(x);
        common.retainAll(y);
        Set<String> all = new HashSet<>(x);
        all.addAll(y);
        int shorter = Math.min(x.size(), y.size());
        return (double) common.size() / all.size() >= SAME || (shorter >= 3 && common.size() == shorter);
    }

    private static Set<String> words(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return new HashSet<>(List.of(AnswerKeys.normalise(text).split(" ")));
    }
}
