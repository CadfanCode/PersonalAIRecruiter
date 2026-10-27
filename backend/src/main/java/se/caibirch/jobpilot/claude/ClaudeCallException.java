package se.caibirch.jobpilot.claude;

public class ClaudeCallException extends RuntimeException {

    private final String stderr;

    public ClaudeCallException(String message) {
        this(message, null, null);
    }

    public ClaudeCallException(String message, String stderr, Throwable cause) {
        super(message, cause);
        this.stderr = stderr;
    }

    /** Captured stderr of the CLI process, may be null or empty. */
    public String stderr() {
        return stderr;
    }
}
