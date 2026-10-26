package se.caibirch.jobpilot.claude;

/** The only way the app reaches Claude (CLAUDE.md hard rule 6). */
public interface ClaudeRunner {

    /** Runs the task and returns the schema-valid structured output, or throws {@link ClaudeCallException}. */
    <T> T run(ClaudeTask<T> task);
}
