package app.jobpilot.cvdb;

import java.util.Locale;

/** Normalises screening-question text so the same question matches across sites (SPEC 9.2). */
public final class AnswerKeys {

    private AnswerKeys() {
    }

    public static String normalise(String question) {
        return question.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }
}
