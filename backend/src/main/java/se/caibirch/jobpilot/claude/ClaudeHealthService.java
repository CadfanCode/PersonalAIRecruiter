package se.caibirch.jobpilot.claude;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import se.caibirch.jobpilot.settings.JobPilotProperties;

/** {@code claude --version} plus a tiny schema call, so a green check means the whole path works. */
@Service
public class ClaudeHealthService {

    public record HealthStatus(boolean ok, String version, long durationMs, String error) {
    }

    public record HealthAnswer(String status) {
    }

    private static final int VERSION_TIMEOUT_SECONDS = 15;

    private final JobPilotProperties.Claude config;
    private final ClaudeRunner runner;

    public ClaudeHealthService(JobPilotProperties props, ClaudeRunner runner) {
        this.config = props.claude();
        this.runner = runner;
    }

    public HealthStatus check() {
        long started = System.nanoTime();
        String version = null;
        try {
            version = version();
            HealthAnswer answer = runner.run(ClaudeTask.of("health",
                    ClaudeResources.prompt("health", java.util.Map.of()),
                    ClaudeResources.schema("health"), HealthAnswer.class));
            boolean ok = "ok".equals(answer.status());
            return new HealthStatus(ok, version, elapsedMs(started), ok ? null : "Unexpected status " + answer.status());
        } catch (ClaudeCallException e) {
            return new HealthStatus(false, version, elapsedMs(started), e.getMessage());
        }
    }

    String version() {
        ProcessBuilder pb = new ProcessBuilder(config.path(), "--version");
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            // The output is one line, so the pipe cannot fill while we wait.
            if (!p.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new ClaudeCallException("'" + config.path() + " --version' timed out");
            }
            byte[] out = p.getInputStream().readAllBytes();
            String text = new String(out, StandardCharsets.UTF_8).trim();
            if (p.exitValue() != 0) {
                throw new ClaudeCallException("'" + config.path() + " --version' exited with " + p.exitValue() + ": " + text);
            }
            return text;
        } catch (IOException e) {
            throw new ClaudeCallException("Cannot start '" + config.path() + "': " + e.getMessage()
                    + ". Set the Claude CLI path in settings.", null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaudeCallException("Interrupted during version check");
        }
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
