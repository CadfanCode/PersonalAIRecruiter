package app.jobpilot.claude;

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

import app.jobpilot.credentials.InMemorySecretStore;
import app.jobpilot.support.PostgresTestConfig;

/** Onboarding step 2 and Settings: provider choice, the API key's trip to the credential store, and the connection test. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, ConnectionIT.Fakes.class})
class ConnectionIT {

    static final String KEY = "AIzaSyTestKeyabcdefghijklmnopqrstuvwxyz-0123";
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
    @Autowired
    ApiKeyVault vault;
    @Autowired
    ProviderSettings providerSettings;

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
        assertThat(s.get("gemini").get("configured").asBoolean()).isFalse();
        assertThat(s.get("secureStore").get("available").asBoolean()).isTrue();
        assertThat(router.selected().id()).isEqualTo("cli");
    }

    @Test
    void theGeminiKeyGoesToTheCredentialStoreOnlyAndIsNeverEchoedOrStoredInTheDatabase() throws Exception {
        JsonNode saved = call(put("/api/claude/connection/gemini-key"), "{\"key\":\"" + KEY + "\",\"model\":\"gemini-pro-latest\"}", 200);

        assertThat(saved.toString()).doesNotContain(KEY).doesNotContain("abcdefghijklmnop");
        assertThat(saved.get("gemini").get("configured").asBoolean()).isTrue();
        assertThat(saved.get("gemini").get("keyHint").asText()).isEqualTo("…0123");
        assertThat(saved.get("gemini").get("model").asText()).isEqualTo("gemini-pro-latest");
        assertThat(secrets.contents()).containsEntry(ApiKeyVault.ACCOUNT, KEY);
        assertThat(jdbc.sql("SELECT string_agg(key || '=' || value, ',') FROM app_setting").query(String.class).single())
                .doesNotContain("AIzaSy").doesNotContain("abcdefghijklmnop");
    }

    @Test
    void showingTheConnectionNeverReadsTheSecretSoALockedKeyringIsNotAskedToUnlock() throws Exception {
        call(put("/api/claude/connection/gemini-key"), "{\"key\":\"" + KEY + "\"}", 200);
        int before = secrets.reads();

        for (int i = 0; i < 3; i++) {
            JsonNode s = call(get("/api/claude/connection"), null, 200);
            assertThat(s.get("gemini").get("configured").asBoolean()).isTrue();
            assertThat(s.get("gemini").get("keyHint").asText()).isEqualTo("…0123");
        }

        assertThat(secrets.reads()).as("the secret is read only when a call needs it").isEqualTo(before);
        call(delete("/api/claude/connection/gemini-key"), null, 200);
        assertThat(call(get("/api/claude/connection"), null, 200).get("gemini").get("configured").asBoolean()).isFalse();
    }

    @Test
    void anObviouslyWrongKeyIsRefusedBeforeItIsSaved() throws Exception {
        call(put("/api/claude/connection/gemini-key"), "{\"key\":\"hello\"}", 400);
        call(put("/api/claude/connection/gemini-key"), "{\"key\":\"  \"}", 400);
        assertThat(secrets.contents()).isEmpty();
    }

    @Test
    void withoutAUsableCredentialStoreTheKeyIsKeptEncryptedNeverAsPlainText() throws Exception {
        secrets.setAvailable(false);

        JsonNode status = call(get("/api/claude/connection"), null, 200);
        assertThat(status.get("secureStore").get("name").asText()).contains("encrypted file");

        call(put("/api/claude/connection/gemini-key"), "{\"key\":\"" + KEY + "\"}", 200);

        assertThat(secrets.contents()).as("nothing goes to the OS store when there is none").isEmpty();
        java.nio.file.Path file = dataDir.resolve("secrets/gemini.enc");
        assertThat(file).exists();
        assertThat(java.nio.file.Files.readString(file)).doesNotContain(KEY).doesNotContain("AIzaSy");
        assertThat(vault.get()).contains(KEY);
        call(delete("/api/claude/connection/gemini-key"), null, 200);
        assertThat(file).doesNotExist();
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
    void choosingGeminiRoutesCallsThereAndDeletingTheKeyFallsBackToTheCli() throws Exception {
        call(put("/api/claude/connection/gemini-key"), "{\"key\":\"" + KEY + "\"}", 200);
        JsonNode chosen = call(put("/api/claude/connection/provider"), "{\"id\":\"gemini\"}", 200);
        assertThat(chosen.get("selected").asText()).isEqualTo("gemini");
        assertThat(chosen.get("chosen").asBoolean()).isTrue();
        assertThat(router.selected().id()).isEqualTo("gemini");

        call(put("/api/claude/connection/provider"), "{\"id\":\"nonsense\"}", 400);

        JsonNode after = call(delete("/api/claude/connection/gemini-key"), null, 200);
        assertThat(after.get("gemini").get("configured").asBoolean()).isFalse();
        assertThat(after.get("selected").asText()).isEqualTo("cli");
        assertThat(secrets.contents()).isEmpty();
    }

    @Test
    void testingGeminiWithoutAKeySaysSoWithoutAnyNetworkCall() throws Exception {
        JsonNode result = call(post("/api/claude/connection/test"), "{\"provider\":\"gemini\"}", 200);
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
        assertThat(ConnectionService.failure("gemini", "Google AI Studio rejected the saved key. Check it", 3).problem()).isEqualTo("INVALID_KEY");
    }
}
