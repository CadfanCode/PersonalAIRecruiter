package app.jobpilot.claude;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persists one row per CLI call and aggregates them for the Settings page (SPEC 5.1). */
@Repository
public class ClaudeCallLog {

    public record Entry(String task, String model, long durationMs, BigDecimal costUsd, Long inputTokens,
                        Long outputTokens, Long cacheReadTokens, Long cacheCreationTokens, boolean success,
                        String errorMessage) {
    }

    public record Totals(long calls, long failures, BigDecimal costUsd, long inputTokens, long outputTokens,
                         long cacheReadTokens, long cacheCreationTokens, long durationMs) {
    }

    public record TaskTotals(String task, long calls, long tokens) {
    }

    private final JdbcClient jdbc;

    public ClaudeCallLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Entry e) {
        jdbc.sql("""
                INSERT INTO claude_call (task, model, duration_ms, cost_usd, input_tokens, output_tokens,
                                         cache_read_tokens, cache_creation_tokens, success, error_message)
                VALUES (:task, :model, :durationMs, :costUsd, :inputTokens, :outputTokens,
                        :cacheReadTokens, :cacheCreationTokens, :success, :errorMessage)
                """)
                .param("task", e.task())
                .param("model", e.model())
                .param("durationMs", e.durationMs())
                .param("costUsd", e.costUsd())
                .param("inputTokens", e.inputTokens())
                .param("outputTokens", e.outputTokens())
                .param("cacheReadTokens", e.cacheReadTokens())
                .param("cacheCreationTokens", e.cacheCreationTokens())
                .param("success", e.success())
                .param("errorMessage", e.errorMessage())
                .update();
    }

    public Totals totals() {
        return jdbc.sql("""
                SELECT count(*) AS calls,
                       count(*) FILTER (WHERE NOT success) AS failures,
                       coalesce(sum(cost_usd), 0) AS cost,
                       coalesce(sum(input_tokens), 0) AS input_tokens,
                       coalesce(sum(output_tokens), 0) AS output_tokens,
                       coalesce(sum(cache_read_tokens), 0) AS cache_read,
                       coalesce(sum(cache_creation_tokens), 0) AS cache_creation,
                       coalesce(sum(duration_ms), 0) AS duration_ms
                FROM claude_call
                """)
                .query((rs, n) -> new Totals(rs.getLong("calls"), rs.getLong("failures"), rs.getBigDecimal("cost"),
                        rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getLong("cache_read"),
                        rs.getLong("cache_creation"), rs.getLong("duration_ms")))
                .single();
    }

    public List<TaskTotals> totalsByTask() {
        return jdbc.sql("""
                SELECT task, count(*) AS calls,
                       coalesce(sum(coalesce(input_tokens, 0) + coalesce(output_tokens, 0) + coalesce(cache_creation_tokens, 0)), 0) AS tokens
                FROM claude_call GROUP BY task ORDER BY tokens DESC, task
                """)
                .query((rs, n) -> new TaskTotals(rs.getString("task"), rs.getLong("calls"), rs.getLong("tokens")))
                .list();
    }
}
