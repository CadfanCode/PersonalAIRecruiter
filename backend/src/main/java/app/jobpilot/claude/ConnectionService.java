package app.jobpilot.claude;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import app.jobpilot.credentials.SecretStore;
import app.jobpilot.settings.JobPilotProperties;

/** Onboarding step 2 and Settings: which provider is used, whether it works, and the API key kept in the OS credential store. */
@Service
public class ConnectionService {

    public record CliStatus(boolean found, String path, String version, String help) {
    }

    public record ApiStatus(boolean configured, String keyHint, String model) {
    }

    public record SecureStoreStatus(boolean available, String name) {
    }

    public record Status(String selected, boolean chosen, CliStatus cli, ApiStatus api, SecureStoreStatus secureStore) {
    }

    /** problem: null when ok, else NOT_INSTALLED, NOT_LOGGED_IN, INVALID_KEY, NO_KEY or OTHER. */
    public record TestResult(boolean ok, String provider, String problem, String message, String help, long durationMs) {
    }

    private static final Pattern AUTH = Pattern.compile("(?i)(log ?in|logged in|authenticat|credential|unauthori|invalid api key|api key|401|oauth|token)");
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private final ProviderRouter router;
    private final ProviderSettings settings;
    private final ApiClaudeRunner api;
    private final ClaudeHealthService health;
    private final SecretStore secrets;
    private final JobPilotProperties.Claude config;

    public ConnectionService(ProviderRouter router, ProviderSettings settings, ApiClaudeRunner api, ClaudeHealthService health,
                             SecretStore secrets, JobPilotProperties props) {
        this.router = router;
        this.settings = settings;
        this.api = api;
        this.health = health;
        this.secrets = secrets;
        this.config = props.claude();
    }

    public Status status() {
        return new Status(settings.selectedId(), settings.chosen(), cli(), new ApiStatus(api.keyConfigured(),
                api.keyHint().orElse(null), settings.apiModel()), new SecureStoreStatus(secrets.available(), secrets.description()));
    }

    private CliStatus cli() {
        try {
            String version = health.version();
            return new CliStatus(true, config.executable(), version, null);
        } catch (ClaudeCallException e) {
            return new CliStatus(false, config.executable(), null, installHelp());
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

    public void saveApiKey(String key, String model) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Paste your Anthropic API key");
        }
        String trimmed = key.trim();
        if (trimmed.length() < 20 || trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("That does not look like an Anthropic API key (they start with sk-ant-)");
        }
        secrets.put(ApiClaudeRunner.KEY_ACCOUNT, trimmed);
        settings.apiKeyRecorded("…" + trimmed.substring(trimmed.length() - 4));
        settings.forgetTest();
        if (model != null) {
            settings.apiModel(model);
        }
    }

    public void deleteApiKey() {
        secrets.delete(ApiClaudeRunner.KEY_ACCOUNT);
        settings.apiKeyForgotten();
        settings.forgetTest();
        if (ProviderSettings.API.equals(settings.selectedId())) {
            settings.select(ProviderSettings.CLI);
        }
    }

    public void apiModel(String model) {
        settings.apiModel(model);
    }

    /** A real (tiny) model call through the given provider; never changes the chosen provider. */
    public TestResult test(String providerId) {
        long started = System.nanoTime();
        AiProvider provider = router.provider(providerId);
        if (ProviderSettings.API.equals(providerId) && !api.keyConfigured()) {
            return new TestResult(false, providerId, "NO_KEY", "No API key is saved yet.", "Paste your key and save it first.", 0);
        }
        try {
            ClaudeHealthService.HealthAnswer answer = provider.run(ClaudeTask.of("health",
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
        if (ProviderSettings.API.equals(providerId)) {
            boolean key = message != null && message.contains("rejected the saved key");
            return new TestResult(false, providerId, key ? "INVALID_KEY" : "OTHER", message, null, durationMs);
        }
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

    /** Whether the user can already get answers: used by onboarding to decide if step 2 is done. */
    public Optional<String> selectedProviderLabel() {
        return Optional.of(router.selected().label());
    }
}
