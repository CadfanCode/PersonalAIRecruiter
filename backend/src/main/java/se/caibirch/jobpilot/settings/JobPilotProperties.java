package se.caibirch.jobpilot.settings;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Static configuration from application.yml. Editable settings (SPEC 13) move to the database
 * with the Settings page.
 */
@ConfigurationProperties(prefix = "jobpilot")
public record JobPilotProperties(Path dataDir, List<String> allowedOrigins, Claude claude, Search search,
                                 JobTech jobtech, Browser browser) {

    public record Claude(String path, String model, int timeoutSeconds, int webTimeoutSeconds, int concurrency) {
    }

    public record Search(int defaultMaxAgeDays, int defaultLimit, int resultsTopN, String defaultYrkesroll) {
    }

    /** apiKey is optional: the live API needs none (SPEC 6.1). */
    public record JobTech(String baseUrl, String taxonomyUrl, String apiKey, int requestsPerSecond) {
    }

    /** The apply browser (SPEC 9.1). file: URLs are only for fixtures in tests. */
    public record Browser(boolean headless, int maxTabs, boolean allowFileUrls) {
    }

    /** The one Spring binds from application.yml; the shorter constructor below is for tests. */
    @ConstructorBinding
    public JobPilotProperties {
    }

    /** Defaults for everything beyond the first three settings; used by tests that only care about those. */
    public JobPilotProperties(Path dataDir, List<String> allowedOrigins, Claude claude) {
        this(dataDir, allowedOrigins, claude, new Search(7, 50, 10, "Systemutvecklare/Programmerare"),
                new JobTech("http://localhost:0", "http://localhost:0", "", 5), new Browser(true, 5, false));
    }

    public Path browserProfileDir() {
        return dataDir.resolve("browser-profile");
    }

    public Path claudeWorkDir() {
        return dataDir.resolve("claude-work");
    }
}
