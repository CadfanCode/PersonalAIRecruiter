package app.jobpilot.claude;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;

/**
 * Current session and weekly usage of the user's Claude subscription. The only source is the CLI itself (through
 * {@link ClaudeRunner}); no credentials are read and no Anthropic endpoint is called. One probe is a tiny call, so
 * the answer is cached for a couple of minutes.
 */
@Service
public class ClaudeLimitsService {

    static final Duration FRESH_FOR = Duration.ofMinutes(2);

    private final ClaudeRunner runner;
    private final Clock clock;
    private UsageLimits cached;

    public ClaudeLimitsService(ClaudeRunner runner, Clock clock) {
        this.runner = runner;
        this.clock = clock;
    }

    /** Returns the cached reading unless it is older than two minutes or {@code refresh} is set; null if the CLI reports none. */
    public synchronized UsageLimits get(boolean refresh) {
        Instant now = clock.instant();
        if (!refresh && cached != null && Duration.between(cached.checkedAt(), now).compareTo(FRESH_FOR) < 0) {
            return cached;
        }
        UsageLimits fresh = runner.limits().map(l -> new UsageLimits(l.session(), l.week(), now)).orElse(null);
        if (fresh != null) {
            cached = fresh;
        }
        return fresh != null ? fresh : cached;
    }
}
