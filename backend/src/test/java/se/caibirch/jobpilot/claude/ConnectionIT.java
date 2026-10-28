package se.caibirch.jobpilot.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

import se.caibirch.jobpilot.credentials.InMemorySecretStore;
import se.caibirch.jobpilot.support.PostgresTestConfig;

/** Onboarding step 2 and Settings: provider choice, the API key's trip to the credential store, and the connection test. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, ConnectionIT.Fakes.class})
class ConnectionIT {

    static final String KEY = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz-0123";

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        InMemorySecretStore secretStore() {
            return new InMemorySecretStore();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    InMemorySecretStore secrets;
    @Autowired
    ProviderRouter router;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM app_setting").update();
        secrets.contents().clear();
        secrets.setAvailable(true);
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
    void startsOnTheCliWithNothingChosenAndNoKey() throws Exception {
        JsonNode s = call(get("/api/claude/connection"), null, 200);
        assertThat(s.get("selected").asText()).isEqualTo("cli");
        assertThat(s.get("chosen").asBoolean()).isFalse();
        assertThat(s.get("api").get("configured").asBoolean()).isFalse();
        assertThat(s.get("secureStore").get("available").asBoolean()).isTrue();
        assertThat(router.selected().id()).isEqualTo("cli");
    }

    @Test
    void theApiKeyGoesToTheCredentialStoreOnlyAndIsNeverEchoedOrStoredInTheDatabase() throws Exception {
        JsonNode saved = call(put("/api/claude/connection/api-key"), "{\"key\":\"" + KEY + "\",\"model\":\"claude-opus-5-5\"}", 200);

        assertThat(saved.toString()).doesNotContain(KEY).doesNotContain("abcdefghijklmnop");
        assertThat(saved.get("api").get("configured").asBoolean()).isTrue();
        assertThat(saved.get("api").get("keyHint").asText()).isEqualTo("…0123");
        assertThat(saved.get("api").get("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(secrets.contents()).containsEntry(ApiClaudeRunner.KEY_ACCOUNT, KEY);
        assertThat(jdbc.sql("SELECT string_agg(key || '=' || value, ',') FROM app_setting").query(String.class).single())
                .doesNotContain("sk-ant").doesNotContain("abcdefghijklmnop");
    }

    @Test
    void showingTheConnectionNeverReadsTheSecretSoALockedKeyringIsNotAskedToUnlock() throws Exception {
        call(put("/api/claude/connection/api-key"), "{\"key\":\"" + KEY + "\"}", 200);
        int before = secrets.reads();

        for (int i = 0; i < 3; i++) {
            JsonNode s = call(get("/api/claude/connection"), null, 200);
            assertThat(s.get("api").get("configured").asBoolean()).isTrue();
            assertThat(s.get("api").get("keyHint").asText()).isEqualTo("…0123");
        }

        assertThat(secrets.reads()).as("the secret is read only when a call needs it").isEqualTo(before);
        call(delete("/api/claude/connection/api-key"), null, 200);
        assertThat(call(get("/api/claude/connection"), null, 200).get("api").get("configured").asBoolean()).isFalse();
    }

    @Test
    void anObviouslyWrongKeyIsRefusedBeforeItIsSaved() throws Exception {
        call(put("/api/claude/connection/api-key"), "{\"key\":\"hello\"}", 400);
        call(put("/api/claude/connection/api-key"), "{\"key\":\"  \"}", 400);
        assertThat(secrets.contents()).isEmpty();
    }

    @Test
    void withoutAUsableCredentialStoreNothingIsSavedAndTheUserIsToldWhy() throws Exception {
        secrets.setAvailable(false);

        JsonNode status = call(get("/api/claude/connection"), null, 200);
        assertThat(status.get("secureStore").get("available").asBoolean()).isFalse();

        JsonNode refused = call(put("/api/claude/connection/api-key"), "{\"key\":\"" + KEY + "\"}", 409);
        assertThat(refused.get("error").asText()).contains("secure credential store");
        assertThat(secrets.contents()).isEmpty();
    }

    @Test
    void choosingTheApiProviderRoutesCallsThereAndDeletingTheKeyFallsBackToTheCli() throws Exception {
        call(put("/api/claude/connection/api-key"), "{\"key\":\"" + KEY + "\"}", 200);
        JsonNode chosen = call(put("/api/claude/connection/provider"), "{\"id\":\"api\"}", 200);
        assertThat(chosen.get("selected").asText()).isEqualTo("api");
        assertThat(chosen.get("chosen").asBoolean()).isTrue();
        assertThat(router.selected().id()).isEqualTo("api");

        call(put("/api/claude/connection/provider"), "{\"id\":\"nonsense\"}", 400);

        JsonNode after = call(delete("/api/claude/connection/api-key"), null, 200);
        assertThat(after.get("api").get("configured").asBoolean()).isFalse();
        assertThat(after.get("selected").asText()).isEqualTo("cli");
        assertThat(secrets.contents()).isEmpty();
    }

    @Test
    void testingTheApiWithoutAKeySaysSoWithoutAnyNetworkCall() throws Exception {
        JsonNode result = call(post("/api/claude/connection/test"), "{\"provider\":\"api\"}", 200);
        assertThat(result.get("ok").asBoolean()).isFalse();
        assertThat(result.get("problem").asText()).isEqualTo("NO_KEY");
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
        assertThat(ConnectionService.failure("api", "The Anthropic API rejected the saved key. Check it", 3).problem()).isEqualTo("INVALID_KEY");
    }
}
