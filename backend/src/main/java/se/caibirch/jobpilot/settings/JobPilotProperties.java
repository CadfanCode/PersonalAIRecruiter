package se.caibirch.jobpilot.settings;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Static configuration from application.yml. Editable settings (SPEC 13) move to the database
 * with the Settings page.
 */
@ConfigurationProperties(prefix = "jobpilot")
public record JobPilotProperties(Path dataDir, List<String> allowedOrigins, Claude claude) {

    public record Claude(String path, String model, int timeoutSeconds, int webTimeoutSeconds, int concurrency) {
    }

    public Path claudeWorkDir() {
        return dataDir.resolve("claude-work");
    }
}
