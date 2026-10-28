package app.jobpilot.jobs;

import java.util.Locale;
import java.util.Set;

/** Rough sv/en guess from common words. The scoring call later confirms or corrects it. */
public final class LanguageDetector {

    private static final Set<String> SWEDISH = Set.of("och", "att", "som", "för", "med", "vi", "du", "är", "till",
            "på", "ett", "det", "har", "av", "om", "dig", "vår", "våra", "tjänsten", "erfarenhet");
    private static final Set<String> ENGLISH = Set.of("and", "the", "to", "of", "with", "we", "you", "are", "for", "in",
            "is", "our", "will", "your", "experience", "have", "be", "as");

    private LanguageDetector() {
    }

    public static String detect(String text) {
        if (text == null || text.isBlank()) {
            return "sv";
        }
        int sv = 0;
        int en = 0;
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
            if (SWEDISH.contains(word)) {
                sv++;
            }
            if (ENGLISH.contains(word)) {
                en++;
            }
        }
        return en > sv ? "en" : "sv";
    }
}
