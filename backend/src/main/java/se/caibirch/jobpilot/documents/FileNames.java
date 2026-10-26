package se.caibirch.jobpilot.documents;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

import se.caibirch.jobpilot.documents.DocumentModel.Kind;

/** ASCII-safe PDF names (SPEC 8.5): CV_Cai_Birch_Comstream.pdf, Personligt_brev_... or Cover_letter_... */
public final class FileNames {

    private static final Pattern LEGAL_SUFFIX = Pattern.compile(
            "(?i)[\\s,]+(ab|aktiebolag|publ|ltd|limited|inc|gmbh|as|a/s|oy|oyj|llc|sverige|nordic)\\.?$");

    private FileNames() {
    }

    public static String of(Kind kind, String language, String fullName, String employer) {
        String prefix = kind == Kind.CV ? "CV" : "sv".equals(language) ? "Personligt_brev" : "Cover_letter";
        return prefix + "_" + ascii(fullName) + "_" + ascii(shortEmployer(employer)) + ".pdf";
    }

    static String shortEmployer(String employer) {
        String e = employer == null ? "" : employer.trim();
        String stripped = e;
        // "Quest Consulting Sverige AB" loses both suffixes, "Friday" stays as it is
        for (int i = 0; i < 3; i++) {
            String next = LEGAL_SUFFIX.matcher(stripped).replaceFirst("");
            if (next.equals(stripped) || next.isBlank()) {
                break;
            }
            stripped = next;
        }
        return stripped;
    }

    static String ascii(String text) {
        if (text == null || text.isBlank()) {
            return "Unknown";
        }
        String s = text.replace("ø", "o").replace("Ø", "O").replace("æ", "ae").replace("Æ", "AE").replace("ß", "ss")
                .replace("ð", "d").replace("þ", "th").replace("ł", "l");
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.length() > 40) {
            s = s.substring(0, 40).replaceAll("_+$", "");
        }
        return s.isEmpty() ? "Unknown" : s;
    }

    @SuppressWarnings("unused")
    private static Locale unused() {
        return Locale.ROOT;
    }
}
