package se.caibirch.jobpilot.claude;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings of the Anthropic API provider. The key itself is never here: it lives in the OS credential store. */
@ConfigurationProperties(prefix = "jobpilot.api")
public record ApiProperties(@DefaultValue("https://api.anthropic.com") String baseUrl,
                            @DefaultValue("claude-sonnet-5-5") String model,
                            @DefaultValue("8192") int maxTokens) {
}
