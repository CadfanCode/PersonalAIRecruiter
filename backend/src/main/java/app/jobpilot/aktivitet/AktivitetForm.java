package app.jobpilot.aktivitet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.jobpilot.apply.FormField;

/**
 * Finds the seven fields of the aktivitetsrapport form by their label or legend (SPEC 10). Everything is mapped in Java,
 * with no model call. If a field is missing or ambiguous the form is not the one we know and nothing is saved.
 */
final class AktivitetForm {

    /** Thrown when the page does not look like the form described in the spec. */
    static final class FormDiffers extends RuntimeException {
        FormDiffers(String message) {
            super(message);
        }
    }

    record Located(FormField yrkesroll, FormField arbetsgivare, Map<String, FormField> omfattning, Map<String, FormField> varFinns,
                   FormField ort, Map<String, FormField> annons, FormField datum) {
    }

    private AktivitetForm() {
    }

    static Located locate(List<FormField> fields) {
        return new Located(
                single(text(fields, "yrkesroll"), "Yrkesroll"),
                single(text(fields, "arbetsgivar"), "Arbetsgivaren"),
                group(fields, "omfattning", "Välj omfattning"),
                group(fields, "var finns jobbet", "Var finns jobbet?"),
                single(text(fields, "välj ort"), "Sök och välj ort"),
                group(fields, "annons", "Svarade du på en annons"),
                single(fields.stream().filter(f -> "date".equals(f.type()) && own(f).contains("datum")).toList(), "Vilket datum"));
    }

    private static List<FormField> text(List<FormField> fields, String word) {
        return fields.stream().filter(f -> f.isText() && !"date".equals(f.type()) && own(f).contains(word)).toList();
    }

    private static FormField single(List<FormField> matches, String what) {
        if (matches.size() != 1) {
            throw new FormDiffers("The form looks different: expected exactly one field for \"" + what + "\" but found " + matches.size()
                    + ". Save the activities by hand.");
        }
        return matches.get(0);
    }

    /** The radio buttons of the group whose legend contains {@code word}, keyed by lower-case option text. */
    private static Map<String, FormField> group(List<FormField> fields, String word, String what) {
        Map<String, FormField> options = new LinkedHashMap<>();
        for (FormField f : fields) {
            if ("radio".equals(f.type()) && (f.legend() == null ? "" : f.legend()).toLowerCase(Locale.ROOT).contains(word)) {
                options.put(f.optionLabel().strip().toLowerCase(Locale.ROOT), f);
            }
        }
        if (options.size() < 2) {
            throw new FormDiffers("The form looks different: no choices found for \"" + what + "\". Save the activities by hand.");
        }
        return options;
    }

    private static String own(FormField f) {
        return f.ownText().toLowerCase(Locale.ROOT);
    }

    static FormField option(Map<String, FormField> group, String label, String what) {
        FormField f = group.get(label.strip().toLowerCase(Locale.ROOT));
        if (f == null) {
            throw new FormDiffers("The form has no \"" + label + "\" choice for " + what + ". Save the activity by hand.");
        }
        return f;
    }
}
