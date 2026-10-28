package app.jobpilot.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ClaudeResourcesTest {

    @Test
    void rendersPromptTemplateWithoutPlaceholders() {
        assertThat(ClaudeResources.prompt("health", Map.of())).contains("Return only data matching the schema");
    }

    @Test
    void loadsSchema() {
        assertThat(ClaudeResources.schema("health")).contains("\"status\"");
    }

    @Test
    void substitutesValuesLiterallyInOnePass() {
        String rendered = ClaudeResources.prompt("test-greeting", Map.of("name", "Cai", "ad", "uses {{name}} and $1 \\ syntax"));

        assertThat(rendered).isEqualTo("Hello Cai, ad: uses {{name}} and $1 \\ syntax\n");
    }

    @Test
    void missingPlaceholderValueFails() {
        assertThatThrownBy(() -> ClaudeResources.prompt("test-greeting", Map.of("name", "Cai")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("{{ad}}");
    }
}
