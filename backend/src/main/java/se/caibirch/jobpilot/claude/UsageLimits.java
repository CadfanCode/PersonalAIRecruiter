package se.caibirch.jobpilot.claude;

import java.time.Instant;

/**
 * The subscription windows the Claude CLI reports: the rolling 5-hour session and the 7-day week. {@code utilization}
 * is a fraction between 0 and 1 (it can pass 1 when the limit is hit); {@code resetsAt} is when the window resets.
 */
public record UsageLimits(Window session, Window week, Instant checkedAt) {

    public record Window(double utilization, Instant resetsAt, String status) {
    }
}
