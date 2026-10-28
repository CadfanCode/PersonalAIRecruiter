package app.jobpilot.claude;

import java.util.Objects;

/**
 * One Claude call: a task name for logging, the rendered prompt, the JSON Schema the answer must
 * satisfy and the type it is bound to.
 *
 * @param webTools true only for web discovery (SPEC 6.2): allows WebSearch and WebFetch and uses
 *                 the longer timeout
 * @param longRunning true for tasks that read a lot of text (CV import), which use the longer
 *                    timeout without web tools
 */
public record ClaudeTask<T>(String name, String prompt, String schemaJson, Class<T> resultType, boolean webTools,
                            boolean longRunning) {

    /** Web and long-running tasks get the long timeout; others the default. */
    public boolean usesLongTimeout() {
        return webTools || longRunning;
    }

    public ClaudeTask(String name, String prompt, String schemaJson, Class<T> resultType, boolean webTools) {
        this(name, prompt, schemaJson, resultType, webTools, false);
    }

    public ClaudeTask {
        Objects.requireNonNull(name);
        Objects.requireNonNull(prompt);
        Objects.requireNonNull(schemaJson);
        Objects.requireNonNull(resultType);
    }

    public static <T> ClaudeTask<T> of(String name, String prompt, String schemaJson, Class<T> resultType) {
        return new ClaudeTask<>(name, prompt, schemaJson, resultType, false, false);
    }

    public static <T> ClaudeTask<T> longRunning(String name, String prompt, String schemaJson, Class<T> resultType) {
        return new ClaudeTask<>(name, prompt, schemaJson, resultType, false, true);
    }
}
