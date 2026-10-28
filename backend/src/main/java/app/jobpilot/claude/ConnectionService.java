package app.jobpilot.claude;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import app.jobpilot.settings.JobPilotProperties;

/** Onboarding step 2 and Settings: which provider is used, which model, whether it works, and the Gemini key kept in the credential store. */
@Service
public class ConnectionService {

    public record CliStatus(boolean found, String path, String version, String help, String model) {
    }

    public record GeminiStatus(boolean configured, String keyHint, String model) {
    }

    public record SecureStoreStatus(boolean available, String name) {
    }

    public record Status(String selected, boolean chosen, CliStatus cli, GeminiStatus gemini, SecureStoreStatus secureStore) {
    }

    /** problem: null when ok, else NOT_INSTALLED, NOT_LOGGED_IN, INVALID_KEY, NO_KEY or OTHER. */
    public record TestResult(boolean ok, String provider, String problem, String message, String help, long durationMs) {
    }

    private static final Pattern AUTH = Pattern.compile("(?i)(log ?in|logged in|authenticat|credential|unauthori|invalid api key|api key|401|oauth|token)");
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private final ProviderRouter router;
    private final ProviderSettings settings;
    private final GeminiProvider gemini;
    private final ApiKeyVault vault;
    private final ClaudeHealthService health;
    private final JobPilotProperties.Claude config;
    private final GeminiModels models;

    public ConnectionService(ProviderRouter router, ProviderSettings settings, GeminiProvider gemini, ApiKeyVault vault,
                             ClaudeHealthService health, JobPilotProperties props, GeminiModels models) {
        this.router = router;
        this.settings = settings;
        this.gemini = gemini;
        this.vault = vault;
        this.health = health;
        this.config = props.claude();
        this.models = models;
    }

    public Status status() {
        return new Status(settings.selectedId(), settings.chosen(), cli(), new GeminiStatus(gemini.keyConfigured(),
                gemini.keyHint().orElse(null), settings.geminiModel()), new SecureStoreStatus(true, vault.description()));
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

    public void saveApiKey(String key, String model) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Paste your Google AI Studio API key");
        }
        String trimmed = key.trim();
        if (trimmed.length() < 20 || trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("That does not look like a Google AI Studio API key");
        }
        vault.put(trimmed);
        settings.keyRecorded("…" + trimmed.substring(trimmed.length() - 4));
        settings.forgetTest();
        if (model != null) {
            settings.geminiModel(model);
        }
    }

    public void deleteApiKey() {
        vault.delete();
        settings.keyForgotten();
        settings.forgetTest();
        if (ProviderSettings.GEMINI.equals(settings.selectedId())) {
            settings.select(ProviderSettings.CLI);
        }
    }

    public void geminiModel(String model) {
        settings.geminiModel(model);
        settings.forgetTest();
    }

    /** Takes effect on the next request: the CLI runner asks for the model on every call. */
    public void cliModel(String model) {
        settings.cliModel(model);
    }

    /** The models the saved key can use, for the selector; an empty list when the key is missing or Google cannot be reached. */
    public java.util.List<GeminiModels.ModelOption> geminiModels() {
        return models.list();
    }

    /** A real (tiny) model call through the given provider; never changes the chosen provider. */
    public TestResult test(String providerId) {
        long started = System.nanoTime();
        AiProvider provider = router.provider(providerId);
        if (ProviderSettings.GEMINI.equals(providerId) && !gemini.keyConfigured()) {
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
        if (ProviderSettings.GEMINI.equals(providerId)) {
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
