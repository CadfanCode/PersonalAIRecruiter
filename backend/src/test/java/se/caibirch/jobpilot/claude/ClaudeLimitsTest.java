package se.caibirch.jobpilot.claude;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class ClaudeLimitsTest {

    static final String STREAM = """
            {"type":"system","subtype":"init"}
            {"type":"rate_limit_event","rate_limit_info":{"status":"allowed_warning","unifiedWindows":{"five_hour":{"utilization":0.07,"resetsAt":1793122200,"status":"allowed"},"seven_day":{"utilization":0.96,"resetsAt":1793138400,"status":"allowed_warning"}}}}
            {"type":"result","subtype":"success"}
            """;

    @Test
    void readsBothWindowsFromTheRateLimitEvent() {
        UsageLimits l = CliClaudeRunner.parseLimits(STREAM, new ObjectMapper()).orElseThrow();

        assertThat(l.session().utilization()).isEqualTo(0.07);
        assertThat(l.session().resetsAt()).isEqualTo(Instant.ofEpochSecond(1793122200));
        assertThat(l.week().utilization()).isEqualTo(0.96);
        assertThat(l.week().status()).isEqualTo("allowed_warning");
    }

    @Test
    void noEventMeansNoReading() {
        assertThat(CliClaudeRunner.parseLimits("{\"type\":\"result\"}\nnot json at all", new ObjectMapper())).isEmpty();
        assertThat(CliClaudeRunner.parseLimits("{\"type\":\"rate_limit_event\"", new ObjectMapper())).isEmpty();
    }

    @Test
    void aReadingIsReusedForTwoMinutesUnlessARefreshIsAsked() {
        AtomicInteger probes = new AtomicInteger();
        ClaudeRunner runner = new ClaudeRunner() {
            @Override
            public <T> T run(ClaudeTask<T> task) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<UsageLimits> limits() {
                probes.incrementAndGet();
                return Optional.of(new UsageLimits(new UsageLimits.Window(0.1, null, "allowed"), null, null));
            }
        };
        Instant[] now = {Instant.parse("2026-10-27T10:00:00Z")};
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return now[0]; }
        };
        ClaudeLimitsService service = new ClaudeLimitsService(runner, clock);

        service.get(false);
        now[0] = now[0].plus(Duration.ofSeconds(90));
        service.get(false);
        assertThat(probes).hasValue(1);

        now[0] = now[0].plus(Duration.ofSeconds(60));
        service.get(false);
        assertThat(probes).hasValue(2);

        service.get(true);
        assertThat(probes).hasValue(3);
    }
}
