package app.jobpilot.claude;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.jobpilot.credentials.InMemorySecretStore;
import app.jobpilot.settings.AppSettings;
import app.jobpilot.settings.JobPilotProperties;

/** The Gemini provider against a local stand-in for generativelanguage.googleapis.com. Nothing here ever reaches the real service. */
class GeminiProviderTest {

    record Pong(String status) {
    }

    static final String KEY = "AIzaSyTestKey-0123456789abcdef";
    static final String PATH = "/v1beta/models/gemini-test-model:generateContent";
    static final String SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
             "properties":{"status":{"type":"string"}},"required":["status"],"additionalProperties":false}""";

    static WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    InMemorySecretStore secrets = new InMemorySecretStore();
    ClaudeCallLog callLog = mock(ClaudeCallLog.class);
    ApiKeyVault vault;
    GeminiProvider provider;

    @BeforeAll
    static void start() {
        wm.start();
    }

    @AfterAll
    static void stop() {
        wm.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        wm.resetAll();
        AppSettings appSettings = mock(AppSettings.class);
        org.mockito.Mockito.when(appSettings.get(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(i -> i.getArgument(1));
        GeminiProperties gemini = new GeminiProperties(wm.baseUrl(), "gemini-test-model", 1000, 4000);
        JobPilotProperties props = new JobPilotProperties(Files.createTempDirectory("jp-gemini"), List.of(),
                new JobPilotProperties.Claude("claude", "", 5, 5, 4));
        vault = new ApiKeyVault(secrets, props);
        vault.put(KEY);
        provider = new GeminiProvider(gemini, new ProviderSettings(appSettings, gemini, props), vault, props, new ObjectMapper(), callLog,
                ClaudeConcurrency.fixed(2));
    }

    static ClaudeTask<Pong> task() {
        return ClaudeTask.of("health", "Say ok.", SCHEMA, Pong.class);
    }

    static String reply(String json) {
        return reply(json, "STOP");
    }

    static String reply(String json, String finish) {
        String escaped = json.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "{\"candidates\":[{\"finishReason\":\"" + finish + "\",\"content\":{\"parts\":[{\"thought\":true,\"text\":\"hmm\"},"
                + "{\"text\":\"" + escaped + "\"}]}}],\"usageMetadata\":{\"promptTokenCount\":11,\"candidatesTokenCount\":7}}";
    }

    @Test
    void sendsTheTasksSchemaAndTheKeyOnlyInTheHeaderAndSkipsThoughtParts() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(reply("{\"status\":\"ok\"}"))));

        Pong pong = provider.run(task());

        assertThat(pong.status()).isEqualTo("ok");
        wm.verify(postRequestedFor(urlEqualTo(PATH)).withHeader("x-goog-api-key", equalTo(KEY))
                .withRequestBody(containing("\"responseMimeType\":\"application/json\""))
                .withRequestBody(containing("\"responseJsonSchema\""))
                .withRequestBody(containing("\"maxOutputTokens\":1000"))
                .withRequestBody(notMatching("(?s).*\\$schema.*"))
                .withRequestBody(notMatching("(?s).*AIzaSy.*")));
        verify(callLog).record(org.mockito.ArgumentMatchers.argThat(e -> e.success() && e.inputTokens() == 11 && e.outputTokens() == 7));
    }

    @Test
    void anAnswerThatBreaksTheSchemaIsRefused() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withBody(reply("{\"status\":5}"))));

        assertThatThrownBy(() -> provider.run(task())).isInstanceOf(ClaudeCallException.class).hasMessageContaining("does not match its schema");
    }

    @Test
    void jsonInsideACodeFenceIsAccepted() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withBody(reply("```json\n{\"status\":\"ok\"}\n```"))));

        assertThat(provider.run(task()).status()).isEqualTo("ok");
    }

    @Test
    void webTasksUseGoogleSearchGroundingInTheSameCall() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withBody(reply("{\"status\":\"ok\"}"))));

        Pong pong = provider.run(new ClaudeTask<>("discoverJobsWeb", "Find jobs.", SCHEMA, Pong.class, true));

        assertThat(pong.status()).isEqualTo("ok");
        wm.verify(1, postRequestedFor(urlEqualTo(PATH)).withRequestBody(containing("google_search")).withRequestBody(containing("responseJsonSchema")));
    }

    @Test
    void ifTheModelRefusesGroundingTogetherWithASchemaItIsDoneInTwoCalls() {
        wm.stubFor(post(urlEqualTo(PATH)).inScenario("two").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":{\"message\":\"Tool use with a response mime type is unsupported\"}}"))
                .willSetStateTo("search"));
        wm.stubFor(post(urlEqualTo(PATH)).inScenario("two").whenScenarioStateIs("search")
                .willReturn(aResponse().withBody(reply("Found two ads: A and B.")))
                .willSetStateTo("structure"));
        wm.stubFor(post(urlEqualTo(PATH)).inScenario("two").whenScenarioStateIs("structure")
                .willReturn(aResponse().withBody(reply("{\"status\":\"ok\"}"))));

        Pong pong = provider.run(new ClaudeTask<>("discoverJobsWeb", "Find jobs.", SCHEMA, Pong.class, true));

        assertThat(pong.status()).isEqualTo("ok");
        wm.verify(3, postRequestedFor(urlEqualTo(PATH)));
        wm.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(containing("Found two ads: A and B.")));
    }

    @Test
    void aRejectedKeyGivesAMessageTheUserCanActOnWithoutRetrying() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400)
                .withBody("{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\"}}")));

        assertThatThrownBy(() -> provider.run(task())).isInstanceOf(ClaudeCallException.class)
                .hasMessageContaining("rejected the saved key").hasMessageNotContaining("AIzaSy");
        wm.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void aBusyServiceIsRetriedAndThenSucceeds() {
        wm.stubFor(post(urlEqualTo(PATH)).inScenario("busy").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503).withHeader("retry-after", "1").withBody("{\"error\":{\"message\":\"high demand\"}}"))
                .willSetStateTo("retry"));
        wm.stubFor(post(urlEqualTo(PATH)).inScenario("busy").whenScenarioStateIs("retry")
                .willReturn(aResponse().withBody(reply("{\"status\":\"ok\"}"))));

        assertThat(provider.run(task()).status()).isEqualTo("ok");
        wm.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void longTasksGetALargerAnswerBudgetThanOrdinaryOnes() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withBody(reply("{\"status\":\"ok\"}"))));

        provider.run(ClaudeTask.longRunning("importCv", "Read this CV.", SCHEMA, Pong.class));

        wm.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(containing("\"maxOutputTokens\":4000")));
    }

    @Test
    void anAnswerCutOffByTheTokenLimitIsReportedAsSuch() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withBody(reply("{\"sta", "MAX_TOKENS"))));

        assertThatThrownBy(() -> provider.run(task())).isInstanceOf(ClaudeCallException.class).hasMessageContaining("cut off");
        wm.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void withoutASavedKeyNothingIsSent() {
        vault.delete();

        assertThatThrownBy(() -> provider.run(task())).isInstanceOf(ClaudeCallException.class).hasMessageContaining("No Google AI Studio key");
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void theEncryptedFileFallbackRoundTripsAndNeverHoldsThePlainKey() throws Exception {
        secrets.setAvailable(false);
        Path dir = Files.createTempDirectory("jp-vault");
        ApiKeyVault fallback = new ApiKeyVault(secrets, new JobPilotProperties(dir, List.of(), new JobPilotProperties.Claude("claude", "", 5, 5, 4)));

        fallback.put(KEY);

        assertThat(fallback.get()).contains(KEY);
        assertThat(Files.readString(dir.resolve("secrets/gemini.enc"))).doesNotContain(KEY);
        fallback.delete();
        assertThat(fallback.get()).isEmpty();
    }

    @Test
    void aTimeoutIsNotRepeatedSoAStageNeverHoldsForFiveTimeLimits() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFixedDelay(7_000).withBody(reply("{\"status\":\"ok\"}"))));

        long start = System.nanoTime();
        assertThatThrownBy(() -> provider.run(task())).isInstanceOf(ClaudeCallException.class).hasMessageContaining("did not answer within");
        assertThat((System.nanoTime() - start) / 1_000_000_000L).as("one attempt of the 5 s limit, not five").isLessThan(9);
        wm.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }
}
