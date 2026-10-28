package se.caibirch.jobpilot.claude;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the Anthropic API provider. {@code longMaxTokens} is the answer budget of long tasks (CV import, role suggestions,
 * web discovery), which can far exceed an ordinary answer.
 * The key itself is never here: it lives in the OS credential store. */
@ConfigurationProperties(prefix = "jobpilot.api")
public record ApiProperties(@DefaultValue("https://api.anthropic.com") String baseUrl,
                            @DefaultValue("claude-sonnet-5-5") String model,
                            @DefaultValue("8192") int maxTokens,
                            @DefaultValue("16000") int longMaxTokens) {
}
