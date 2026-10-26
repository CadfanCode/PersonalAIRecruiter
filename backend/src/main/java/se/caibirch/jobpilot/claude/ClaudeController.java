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

    public ClaudeController(ClaudeHealthService health, ClaudeCallLog callLog) {
        this.health = health;
        this.callLog = callLog;
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
