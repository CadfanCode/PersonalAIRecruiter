package se.caibirch.jobpilot.apply;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Claude's answer for {@code mapFormFields} (SPEC 5.4). Nothing in it is trusted until {@link ActionValidator} has checked it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FormActions(List<MappedAction> actions, List<Question> questionsForUser) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MappedAction(String fieldId, String action, String value, String file, String reason, boolean needsUser,
                               String source) {
    }

    public record Question(String fieldId, String question) {
    }
}
