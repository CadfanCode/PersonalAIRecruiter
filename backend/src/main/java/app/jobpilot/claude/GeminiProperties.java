package app.jobpilot.claude;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the Google AI Studio (Gemini) provider. The default model is Google's own "latest" alias so it does not go stale.
 * {@code maxTokens} and {@code longMaxTokens} (the long tasks: CV import, role suggestions, web discovery) are generous because the
 * Flash models count their "thinking" tokens against the same limit.
 * The key itself is never here: it lives in the OS credential store (or, without one, in an encrypted file, see {@link ApiKeyVault}).
 */
@ConfigurationProperties(prefix = "jobpilot.gemini")
public record GeminiProperties(@DefaultValue("https://generativelanguage.googleapis.com") String baseUrl,
                               @DefaultValue("gemini-flash-latest") String model,
                               @DefaultValue("16384") int maxTokens,
                               @DefaultValue("32768") int longMaxTokens) {
}
