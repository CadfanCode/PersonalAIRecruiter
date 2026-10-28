package app.jobpilot.apply;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One input, select or textarea as read from the page. {@code id} is the data-jp-id (prefixed with the frame for iframes). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FormField(String id, int frame, String tag, String type, String name, String label, String ariaLabel,
                        String placeholder, String title, String legend, String nearbyText, String optionLabel,
                        String groupName, boolean required, boolean disabled, boolean visible, String value,
                        boolean checked, boolean multiple, String accept, boolean autocomplete, List<Option> options) {

    public record Option(String value, String label) {
    }

    /** Every piece of text tied to the control, for the consent check. */
    public String allText() {
        return Stream.of(label, ariaLabel, title, legend, nearbyText, optionLabel, placeholder, name)
                .filter(s -> s != null && !s.isBlank()).collect(Collectors.joining(" | "));
    }

    /** The control's own words: its label, aria-label, title, placeholder and name. Not the headings around it. */
    public String ownText() {
        return Stream.of(label, ariaLabel, title, placeholder, name)
                .filter(s -> s != null && !s.isBlank()).collect(Collectors.joining(" | "));
    }

    /**
     * The text the consent rule reads. A checkbox, radio or select is judged by everything around it (legend,
     * nearby text, option label). A text or file field is judged only by its own words: "Personuppgifter" as a
     * section heading or "Accepterade filformat: PDF" next to an upload must not block ordinary fields.
     */
    public String consentText() {
        return isChoice() || "select".equals(tag) ? allText() : ownText();
    }

    /** The question as the user would read it, used as the answers-memory key text. */
    public String questionText() {
        for (String s : new String[] {legend, label, ariaLabel, nearbyText, placeholder, name}) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return id;
    }

    public boolean isText() {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return "textarea".equals(tag) || List.of("text", "email", "tel", "url", "number", "search", "date", "month", "textarea").contains(t);
    }

    public boolean isChoice() {
        return "checkbox".equals(type) || "radio".equals(type);
    }

    public FormField withFrame(int index) {
        return new FormField(id, index, tag, type, name, label, ariaLabel, placeholder, title, legend, nearbyText, optionLabel,
                groupName, required, disabled, visible, value, checked, multiple, accept, autocomplete,
                options == null ? List.of() : options);
    }
}
