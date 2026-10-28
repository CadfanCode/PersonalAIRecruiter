package app.jobpilot.claude;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import app.jobpilot.credentials.SecretStore;
import app.jobpilot.settings.JobPilotProperties;

/** Onboarding step 2 and Settings: the Claude Code program, which model it uses and whether it works. */
@Service
public class ConnectionService {

    public record CliStatus(boolean found, String path, String version, String help, String model) {
    }

    /** The OS credential store that holds saved employer-portal logins (Settings shows where they are kept). */
    public record SecureStoreStatus(boolean available, String name) {
    }

    public record Status(String selected, boolean chosen, CliStatus cli, SecureStoreStatus secureStore) {
    }

    /** problem: null when ok, else NOT_INSTALLED, NOT_LOGGED_IN or OTHER. */
    public record TestResult(boolean ok, String provider, String problem, String message, String help, long durationMs) {
    }

    private static final Pattern AUTH = Pattern.compile("(?i)(log ?in|logged in|authenticat|credential|unauthori|invalid api key|api key|401|oauth|token)");
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private final ClaudeRunner runner;
    private final SecretStore secrets;
    private final ProviderSettings settings;
    private final ClaudeHealthService health;
    private final JobPilotProperties.Claude config;

    public ConnectionService(ClaudeRunner runner, SecretStore secrets, ProviderSettings settings, ClaudeHealthService health, JobPilotProperties props) {
        this.runner = runner;
        this.secrets = secrets;
        this.settings = settings;
        this.health = health;
        this.config = props.claude();
    }

    public Status status() {
        return new Status(settings.selectedId(), settings.chosen(), cli(), new SecureStoreStatus(secrets.available(), secrets.description()));
    }

    private CliStatus cli() {
        try {
            String version = health.version();
            return new CliStatus(true, config.executable(), version, null, settings.cliModel());
        } catch (ClaudeCallException e) {
            return new CliStatus(false, config.executable(), null, installHelp(), settings.cliModel());
        }
    }

    static String installHelp() {
        return WINDOWS
                ? "Install Claude Code: open PowerShell and run  irm https://claude.ai/install.ps1 | iex  then type  claude  and sign in."
                : "Install Claude Code: open a terminal and run  curl -fsSL https://claude.ai/install.sh | bash  then type  claude  and sign in.";
    }

    public void select(String provider) {
        settings.select(provider);
        settings.forgetTest();
    }

    /** Takes effect on the next request: the CLI runner asks for the model on every call. */
    public void cliModel(String model) {
        settings.cliModel(model);
    }

    /** A real (tiny) model call through Claude Code. */
    public TestResult test() {
        long started = System.nanoTime();
        String providerId = ProviderSettings.CLI;
        try {
            ClaudeHealthService.HealthAnswer answer = runner.run(ClaudeTask.of("health",
                    ClaudeResources.prompt("health", Map.of()), ClaudeResources.schema("health"), ClaudeHealthService.HealthAnswer.class));
            boolean ok = "ok".equals(answer.status());
            if (ok) {
                settings.testPassed(providerId);
            }
            return new TestResult(ok, providerId, ok ? null : "OTHER", ok ? "Connected." : "Unexpected answer: " + answer.status(), null, ms(started));
        } catch (ClaudeCallException e) {
            return failure(providerId, e.getMessage(), ms(started));
        }
    }

    static TestResult failure(String providerId, String message, long durationMs) {
        if (message != null && message.startsWith("Cannot start")) {
            return new TestResult(false, providerId, "NOT_INSTALLED", "Claude Code was not found on this computer.", installHelp(), durationMs);
        }
        if (message != null && AUTH.matcher(message).find()) {
            return new TestResult(false, providerId, "NOT_LOGGED_IN", "Claude Code is installed but not signed in.",
                    "Open a terminal, type  claude  and sign in with your Claude account, then test again.", durationMs);
        }
        return new TestResult(false, providerId, "OTHER", message, null, durationMs);
    }

    private static long ms(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
