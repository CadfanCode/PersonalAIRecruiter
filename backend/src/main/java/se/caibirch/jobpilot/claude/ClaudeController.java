package se.caibirch.jobpilot.claude;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/claude")
public class ClaudeController {

    public record Usage(ClaudeCallLog.Totals totals, List<ClaudeCallLog.TaskTotals> byTask) {
    }

    private final ClaudeHealthService health;
    private final ClaudeCallLog callLog;
    private final ClaudeLimitsService limits;

    public ClaudeController(ClaudeHealthService health, ClaudeCallLog callLog, ClaudeLimitsService limits) {
        this.health = health;
        this.callLog = callLog;
        this.limits = limits;
    }

    public record Limits(UsageLimits limits, String error) {
    }

    /** Session (5 h) and weekly usage of the Claude subscription, read from the CLI. Always 200. */
    @GetMapping("/limits")
    public Limits limits(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean refresh) {
        try {
            UsageLimits l = limits.get(refresh);
            return new Limits(l, l == null ? "The Claude CLI did not report any usage limits" : null);
        } catch (ClaudeCallException e) {
            return new Limits(null, e.getMessage());
        }
    }

    /** Always 200; {@code ok=false} with an error text when the CLI is missing, not signed in or misbehaving. */
    @GetMapping("/health")
    public ClaudeHealthService.HealthStatus health() {
        return health.check();
    }

    @GetMapping("/usage")
    public Usage usage() {
        return new Usage(callLog.totals(), callLog.totalsByTask());
    }
}
