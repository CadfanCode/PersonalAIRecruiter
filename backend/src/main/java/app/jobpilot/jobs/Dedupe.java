package app.jobpilot.jobs;

import java.util.Locale;

/** {@code dedupe_key = lowercase(employer | normalised title | municipality)} (SPEC 4). */
public final class Dedupe {

    private Dedupe() {
    }

    public static String key(String employer, String title, String municipality) {
        return squash(employer) + "|" + normaliseTitle(title) + "|" + squash(municipality);
    }

    /** Lowercase, without "(m/k/x)" style suffixes and punctuation, with single spaces. */
    public static String normaliseTitle(String title) {
        if (title == null) {
            return "";
        }
        String t = title.toLowerCase(Locale.ROOT).replaceAll("\\((?:[mfkdx]\\s*/\\s*)+[mfkdx]\\)", " ");
        return squash(t.replaceAll("[^\\p{L}\\p{N}]+", " "));
    }

    private static String squash(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }
}
