package app.jobpilot.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import app.jobpilot.support.PostgresTestConfig;

/** Onboarding step 2 and Settings: Claude Code as the only provider, its model, and the connection test. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, ConnectionIT.Fakes.class})
class ConnectionIT {

    static java.nio.file.Path dataDir;

    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry registry) throws java.io.IOException {
        dataDir = java.nio.file.Files.createTempDirectory("jobpilot-connection");
        registry.add("jobpilot.data-dir", () -> dataDir.toString());
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        ClaudeRunner fakeRunner() {
            return new FakeClaudeRunner().respond("health", "{\"status\":\"ok\"}");
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    ProviderRouter router;
    @Autowired
    ProviderSettings providerSettings;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM app_setting").update();
    }

    JsonNode call(MockHttpServletRequestBuilder request, String body, int status) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        String text = mvc.perform(request).andExpect(MockMvcResultMatchers.status().is(status)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return text.isBlank() ? null : mapper.readTree(text);
    }

    @Test
    void startsOnClaudeCodeWithNothingConfirmed() throws Exception {
        JsonNode s = call(get("/api/claude/connection"), null, 200);
        assertThat(s.get("selected").asText()).isEqualTo("cli");
        assertThat(s.get("chosen").asBoolean()).isFalse();
        assertThat(s.has("gemini")).isFalse();
        assertThat(router.selected().id()).isEqualTo("cli");
    }

    @Test
    void anInstallThatStoredGeminiIsReadAsClaudeCodeAndAsksToConfirmAgain() throws Exception {
        jdbc.sql("INSERT INTO app_setting(key, value) VALUES ('claude.provider', 'gemini'), ('claude.tested', 'gemini')").update();

        JsonNode s = call(get("/api/claude/connection"), null, 200);

        assertThat(s.get("selected").asText()).isEqualTo("cli");
        assertThat(s.get("chosen").asBoolean()).as("must be confirmed again").isFalse();
        assertThat(providerSettings.selectedWorks()).isFalse();
    }

    @Test
    void choosingClaudeCodeIsRememberedAndOtherProvidersAreRefused() throws Exception {
        JsonNode chosen = call(put("/api/claude/connection/provider"), "{\"id\":\"cli\"}", 200);
        assertThat(chosen.get("chosen").asBoolean()).isTrue();
        call(put("/api/claude/connection/provider"), "{\"id\":\"gemini\"}", 400);
    }

    @Test
    void aPassedTestIsRemembered() throws Exception {
        JsonNode result = call(post("/api/claude/connection/test"), null, 200);
        assertThat(result.get("ok").asBoolean()).isTrue();
        assertThat(providerSettings.selectedWorks()).isTrue();
    }

    @Test
    void theClaudeCodeModelIsPassedOnTheNextRequestWithoutARestart() throws Exception {
        JsonNode s = call(put("/api/claude/connection/cli-model"), "{\"model\":\"opus\"}", 200);
        assertThat(s.get("cli").get("model").asText()).isEqualTo("opus");
        assertThat(providerSettings.cliModel()).isEqualTo("opus");

        call(put("/api/claude/connection/cli-model"), "{\"model\":\"\"}", 200);
        assertThat(providerSettings.cliModel()).isEmpty();
    }

    @Test
    void cliFailuresAreToldApartSoTheUserKnowsWhatToDo() {
        var missing = ConnectionService.failure("cli", "Cannot start 'claude': error=2, No such file", 3);
        assertThat(missing.problem()).isEqualTo("NOT_INSTALLED");
        assertThat(missing.help()).contains("claude");

        var notSignedIn = ConnectionService.failure("cli", "Claude CLI exited with code 1: Invalid API key · Please run /login", 3);
        assertThat(notSignedIn.problem()).isEqualTo("NOT_LOGGED_IN");
        assertThat(notSignedIn.help()).contains("sign in");

        assertThat(ConnectionService.failure("cli", "timed out after 180 s", 3).problem()).isEqualTo("OTHER");
    }
}
