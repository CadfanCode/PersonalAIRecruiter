package se.caibirch.jobpilot.claude;

import org.springframework.stereotype.Component;

import se.caibirch.jobpilot.settings.AppSettings;

/** Which provider the user chose, and the API model they picked; stored in app_setting. */
@Component
public class ProviderSettings {

    public static final String CLI = "cli";
    public static final String API = "api";
    static final String PROVIDER_KEY = "claude.provider";
    static final String MODEL_KEY = "api.model";
    static final String TESTED_KEY = "claude.tested";
    static final String KEY_HINT = "api.keyHint";

    private final AppSettings settings;
    private final ApiProperties api;

    public ProviderSettings(AppSettings settings, ApiProperties api) {
        this.settings = settings;
        this.api = api;
    }

    public String selectedId() {
        String id = settings.get(PROVIDER_KEY, CLI);
        return API.equals(id) ? API : CLI;
    }

    public boolean chosen() {
        return settings.get(PROVIDER_KEY).isPresent();
    }

    public void select(String id) {
        if (!CLI.equals(id) && !API.equals(id)) {
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
    public boolean apiKeySaved() {
        return settings.get(KEY_HINT).isPresent();
    }

    public java.util.Optional<String> apiKeyHint() {
        return settings.get(KEY_HINT);
    }

    public void apiKeyRecorded(String hint) {
        settings.put(KEY_HINT, hint);
    }

    public void apiKeyForgotten() {
        settings.delete(KEY_HINT);
    }

    public String apiModel() {
        String saved = settings.get(MODEL_KEY, "");
        return saved.isBlank() ? api.model() : saved;
    }

    public void apiModel(String model) {
        if (model == null || model.isBlank()) {
            settings.delete(MODEL_KEY);
        } else {
            settings.put(MODEL_KEY, model.trim());
        }
    }
}
