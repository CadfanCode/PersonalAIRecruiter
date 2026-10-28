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
