package se.caibirch.jobpilot.claude;

import java.util.Optional;

/**
 * One way of reaching the model. The app talks to {@link ClaudeRunner}; the {@link ProviderRouter} behind it hands each call to
 * the provider the user chose in onboarding or Settings. Adding another provider means one more bean of this type (ADR 0014).
 */
public interface AiProvider {

    /** Stable id stored in the settings, for example "cli" or "api". */
    String id();

    /** What the user sees, for example "Claude Code subscription". */
    String label();

    /** Runs the task and returns the schema-valid structured output, or throws {@link ClaudeCallException}. */
    <T> T run(ClaudeTask<T> task);

    /** Subscription usage windows where the provider reports them; empty otherwise. */
    default Optional<UsageLimits> limits() {
        return Optional.empty();
    }
}
