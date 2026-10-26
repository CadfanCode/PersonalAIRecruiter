package se.caibirch.jobpilot.claude;

import java.util.Objects;

/**
 * One Claude call: a task name for logging, the rendered prompt, the JSON Schema the answer must
 * satisfy and the type it is bound to.
 *
 * @param webTools true only for web discovery (SPEC 6.2): allows WebSearch and WebFetch and uses
 *                 the longer timeout
 */
public record ClaudeTask<T>(String name, String prompt, String schemaJson, Class<T> resultType, boolean webTools) {

    public ClaudeTask {
        Objects.requireNonNull(name);
        Objects.requireNonNull(prompt);
        Objects.requireNonNull(schemaJson);
        Objects.requireNonNull(resultType);
    }

    public static <T> ClaudeTask<T> of(String name, String prompt, String schemaJson, Class<T> resultType) {
        return new ClaudeTask<>(name, prompt, schemaJson, resultType, false);
    }
}
