package app.jobpilot.claude;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
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

/** The API provider against a local stand-in for api.anthropic.com. Nothing here ever reaches the real service. */
class ApiClaudeRunnerTest {

    record Pong(String status) {
    }

    static final String SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
             "properties":{"status":{"type":"string"}},"required":["status"],"additionalProperties":false}""";

    static WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    InMemorySecretStore secrets = new InMemorySecretStore();
    ClaudeCallLog callLog = mock(ClaudeCallLog.class);
    ApiClaudeRunner runner;

    @BeforeAll
    static void start() {
        wm.start();
    }

    @AfterAll
    static void stop() {
        wm.stop();
    }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        secrets.put(ApiClaudeRunner.KEY_ACCOUNT, "sk-ant-test-0123456789abcdef");
        AppSettings appSettings = mock(AppSettings.class);
        org.mockito.Mockito.when(appSettings.get(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(i -> i.getArgument(1));
        ApiProperties api = new ApiProperties(wm.baseUrl(), "claude-test-model", 1000, 4000);
        JobPilotProperties props = new JobPilotProperties(Path.of("x"), List.of(), new JobPilotProperties.Claude("claude", "", 5, 5, 4));
        runner = new ApiClaudeRunner(api, new ProviderSettings(appSettings, api), secrets, props, new ObjectMapper(), callLog,
                ClaudeConcurrency.fixed(2));
    }

    static ClaudeTask<Pong> task() {
        return ClaudeTask.of("health", "Say ok.", SCHEMA, Pong.class);
    }

    static String toolUse(String json) {
        return "{\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":11,\"output_tokens\":7},\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\","
                + "\"name\":\"respond\",\"input\":" + json + "}]}";
    }

    @Test
    void forcesTheRespondToolWithTheTasksSchemaAndSendsTheKeyOnlyInTheHeader() {
        wm.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody(toolUse("{\"status\":\"ok\"}"))));

        Pong pong = runner.run(task());

        assertThat(pong.status()).isEqualTo("ok");
        wm.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withHeader("x-api-key", equalTo("sk-ant-test-0123456789abcdef"))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .withRequestBody(equalToJson("""
                        {"model":"claude-test-model","max_tokens":1000,"tool_choice":{"type":"tool","name":"respond"},
                         "messages":[{"role":"user","content":"Say ok."}],
                         "tools":[{"name":"respond","input_schema":{"type":"object","properties":{"status":{"type":"string"}},
                                   "required":["status"],"additionalProperties":false}}]}""", true, true)));
        wm.verify(postRequestedFor(urlEqualTo("/v1/messages")).withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*sk-ant.*")));
        verify(callLog).record(org.mockito.ArgumentMatchers.argThat(e -> e.success() && e.inputTokens() == 11 && e.outputTokens() == 7));
    }

    @Test
    void anAnswerThatBreaksTheSchemaIsRefused() {
        wm.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse().withBody(toolUse("{\"status\":5}"))));

        assertThatThrownBy(() -> runner.run(task())).isInstanceOf(ClaudeCallException.class).hasMessageContaining("does not match its schema");
    }

    @Test
    void proseInsteadOfTheToolIsAnsweredWithARequestForTheToolAndThenAccepted() {
        wm.stubFor(post(urlEqualTo("/v1/messages")).inScenario("prose").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withBody("{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"Sure!\"}]}"))
                .willSetStateTo("asked"));
        wm.stubFor(post(urlEqualTo("/v1/messages")).inScenario("prose").whenScenarioStateIs("asked")
                .willReturn(aResponse().withBody(toolUse("{\"status\":\"ok\"}"))));

        assertThat(runner.run(task()).status()).isEqualTo("ok");
        wm.verify(2, postRequestedFor(urlEqualTo("/v1/messages")));
        wm.verify(postRequestedFor(urlEqualTo("/v1/messages")).withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("Call the respond tool now")));
    }

    @Test
    void webTasksOfferTheWebSearchToolAndContinueAPausedTurn() {
        wm.stubFor(post(urlEqualTo("/v1/messages")).inScenario("web").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withBody("{\"stop_reason\":\"pause_turn\",\"content\":[{\"type\":\"server_tool_use\",\"id\":\"s1\",\"name\":\"web_search\",\"input\":{\"query\":\"x\"}}]}"))
                .willSetStateTo("continued"));
        wm.stubFor(post(urlEqualTo("/v1/messages")).inScenario("web").whenScenarioStateIs("continued")
                .willReturn(aResponse().withBody(toolUse("{\"status\":\"ok\"}"))));

        Pong pong = runner.run(new ClaudeTask<>("discoverJobsWeb", "Find jobs.", SCHEMA, Pong.class, true));

        assertThat(pong.status()).isEqualTo("ok");
        wm.verify(postRequestedFor(urlEqualTo("/v1/messages")).withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("web_search_20250305"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("\"auto\"")));
    }

    @Test
    void aRejectedKeyGivesAMessageTheUserCanActOnWithoutRetrying() {
        wm.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse().withStatus(401)
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}")));

        assertThatThrownBy(() -> runner.run(task())).isInstanceOf(ClaudeCallException.class)
                .hasMessageContaining("rejected the saved key").hasMessageNotContaining("sk-ant");
        wm.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void aBusyServiceIsRetriedAndThenSucceeds() {
        wm.stubFor(post(urlEqualTo("/v1/messages")).inScenario("busy").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("retry-after", "1").withBody("{\"error\":{\"message\":\"slow down\"}}"))
                .willSetStateTo("retry"));
        wm.stubFor(post(urlEqualTo("/v1/messages")).inScenario("busy").whenScenarioStateIs("retry")
                .willReturn(aResponse().withBody(toolUse("{\"status\":\"ok\"}"))));

        assertThat(runner.run(task()).status()).isEqualTo("ok");
        wm.verify(2, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void longTasksGetALargerAnswerBudgetThanOrdinaryOnes() {
        wm.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse().withBody(toolUse("{\"status\":\"ok\"}"))));

        runner.run(ClaudeTask.longRunning("importCv", "Read this CV.", SCHEMA, Pong.class));

        wm.verify(postRequestedFor(urlEqualTo("/v1/messages")).withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("\"max_tokens\":4000")));
    }

    @Test
    void anAnswerCutOffByTheTokenLimitIsReportedAsSuchAndNotRetriedAsProse() {
        wm.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse()
                .withBody("{\"stop_reason\":\"max_tokens\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"respond\",\"input\":{\"sta\":1}}]}")));

        assertThatThrownBy(() -> runner.run(task())).isInstanceOf(ClaudeCallException.class).hasMessageContaining("cut off");
        wm.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void withoutASavedKeyNothingIsSent() {
        secrets.delete(ApiClaudeRunner.KEY_ACCOUNT);

        assertThatThrownBy(() -> runner.run(task())).isInstanceOf(ClaudeCallException.class).hasMessageContaining("No Anthropic API key");
        assertThat(wm.getAllServeEvents()).isEmpty();
    }
}
