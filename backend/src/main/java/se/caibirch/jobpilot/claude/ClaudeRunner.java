package se.caibirch.jobpilot.claude;

/** The only way the app reaches Claude (CLAUDE.md hard rule 6). */
public interface ClaudeRunner {

    /** Runs the task and returns the schema-valid structured output, or throws {@link ClaudeCallException}. */
    <T> T run(ClaudeTask<T> task);

    /** The subscription's session and weekly usage as the CLI reports it, or empty if it does not report any. */
    default java.util.Optional<UsageLimits> limits() {
        return java.util.Optional.empty();
    }
}
