package app.jobpilot.claude;

import org.springframework.stereotype.Component;

import app.jobpilot.settings.AppSettings;
import app.jobpilot.settings.JobPilotProperties;

/** Which provider the user chose and the models they picked for each; stored in app_setting. */
@Component
public class ProviderSettings {

    public static final String CLI = "cli";
    public static final String GEMINI = "gemini";
    static final String PROVIDER_KEY = "claude.provider";
    static final String MODEL_KEY = "gemini.model";
    static final String CLI_MODEL_KEY = "claude.model";
    static final String TESTED_KEY = "claude.tested";
    static final String KEY_HINT = "gemini.keyHint";

    private final AppSettings settings;
    private final GeminiProperties gemini;
    private final JobPilotProperties.Claude claude;

    public ProviderSettings(AppSettings settings, GeminiProperties gemini, JobPilotProperties props) {
        this.settings = settings;
        this.gemini = gemini;
        this.claude = props.claude();
    }

    public String selectedId() {
        String id = settings.get(PROVIDER_KEY, CLI);
        return GEMINI.equals(id) ? GEMINI : CLI;
    }

    /** False for a database from before the Anthropic API option was removed ("api"): that user is asked to choose again. */
    public boolean chosen() {
        return settings.get(PROVIDER_KEY).filter(id -> CLI.equals(id) || GEMINI.equals(id)).isPresent();
    }

    public void select(String id) {
        if (!CLI.equals(id) && !GEMINI.equals(id)) {
            throw new IllegalArgumentException("Unknown provider " + id);
        }
        settings.put(PROVIDER_KEY, id);
    }

    /** True when the provider chosen now is the one whose connection test last passed (changing the key or provider resets it). */
    public boolean selectedWorks() {
        return selectedId().equals(settings.get(TESTED_KEY, ""));
    }

    public void testPassed(String providerId) {
        settings.put(TESTED_KEY, providerId);
    }

    public void forgetTest() {
        settings.delete(TESTED_KEY);
    }

    /**
     * That a key was saved, and its last four characters, are remembered here so that showing the connection never has to read the
     * secret itself: reading it can raise an unlock prompt on a locked keyring or Keychain. The key stays only in the credential store.
     */
    public boolean keySaved() {
        return settings.get(KEY_HINT).isPresent();
    }

    public java.util.Optional<String> keyHint() {
        return settings.get(KEY_HINT);
    }

    public void keyRecorded(String hint) {
        settings.put(KEY_HINT, hint);
    }

    public void keyForgotten() {
        settings.delete(KEY_HINT);
    }

    public String geminiModel() {
        String saved = settings.get(MODEL_KEY, "");
        return saved.isBlank() ? gemini.model() : saved;
    }

    /** Read on every request, so a change in Settings applies to the next call without a restart. Blank means the CLI's own default. */
    public String cliModel() {
        String saved = settings.get(CLI_MODEL_KEY, "");
        if (!saved.isBlank()) {
            return saved;
        }
        return claude.model() == null ? "" : claude.model();
    }

    public void cliModel(String model) {
        if (model == null || model.isBlank()) {
            settings.delete(CLI_MODEL_KEY);
        } else {
            settings.put(CLI_MODEL_KEY, model.trim());
        }
    }

    public void geminiModel(String model) {
        if (model == null || model.isBlank()) {
            settings.delete(MODEL_KEY);
        } else {
            settings.put(MODEL_KEY, model.trim());
        }
    }
}
