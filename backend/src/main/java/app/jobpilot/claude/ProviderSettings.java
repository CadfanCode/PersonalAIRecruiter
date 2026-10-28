package app.jobpilot.claude;

import org.springframework.stereotype.Component;

import app.jobpilot.settings.AppSettings;
import app.jobpilot.settings.JobPilotProperties;

/** Which provider the user chose and the models they picked for each; stored in app_setting. */
@Component
public class ProviderSettings {

    public static final String CLI = "cli";
    static final String PROVIDER_KEY = "claude.provider";
    static final String CLI_MODEL_KEY = "claude.model";
    static final String TESTED_KEY = "claude.tested";

    private final AppSettings settings;
    private final JobPilotProperties.Claude claude;

    public ProviderSettings(AppSettings settings, JobPilotProperties props) {
        this.settings = settings;
        this.claude = props.claude();
    }

    /** Claude Code is the only provider. Whatever an older install stored ("gemini", "api") is read as "cli". */
    public String selectedId() {
        return CLI;
    }

    /** False for a database that never confirmed Claude Code, or one from before Gemini was removed ("gemini", "api"): that user is asked again. */
    public boolean chosen() {
        return settings.get(PROVIDER_KEY).filter(CLI::equals).isPresent();
    }

    public void select(String id) {
        if (!CLI.equals(id)) {
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
}
