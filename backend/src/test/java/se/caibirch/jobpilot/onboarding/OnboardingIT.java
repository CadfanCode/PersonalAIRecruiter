package se.caibirch.jobpilot.onboarding;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import se.caibirch.jobpilot.credentials.InMemorySecretStore;
import se.caibirch.jobpilot.support.PostgresTestConfig;

/** First-run setup from an empty install to the pipeline, with the API provider answering from a local stand-in server. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, OnboardingIT.Fakes.class})
class OnboardingIT {

    static final String KEY = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz-0123";
    static WireMockServer anthropic = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @BeforeAll
    static void start() {
        anthropic.start();
    }

    @AfterAll
    static void stop() {
        anthropic.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        if (!anthropic.isRunning()) {
            anthropic.start();
        }
        registry.add("jobpilot.api.base-url", anthropic::baseUrl);
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

    @BeforeEach
    void emptyInstall() {
        anthropic.resetAll();
        jdbc.sql("TRUNCATE cv_entry, profile, tag, skill, known_gap, answers_memory RESTART IDENTITY CASCADE").update();
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

    void claudeAnswers(String json) {
        anthropic.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/v1/messages")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("{\"stop_reason\":\"tool_use\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"respond\",\"input\":" + json + "}]}")));
    }

    void addCv() throws Exception {
        call(put("/api/profile"), "{\"fullName\":\"Alex Lindgren\",\"headline\":\"Nurse\",\"email\":\"a@example.com\",\"city\":\"Uppsala\",\"languages\":[]}", 200);
        call(post("/api/cv/entries"), "{\"kind\":\"WORK\",\"title\":\"Sjuksköterska\",\"organisation\":\"Akademiska\",\"startYm\":\"2020-01\",\"ongoing\":true}", 201);
    }

    @Test
    void anEmptyInstallStartsAtTheCvStepAndEachStepOpensTheNext() throws Exception {
        JsonNode s = call(get("/api/onboarding"), null, 200);
        assertThat(s.get("nextStep").asText()).isEqualTo("CV");
        assertThat(s.get("complete").asBoolean()).isFalse();
        assertThat(s.get("cv").get("present").asBoolean()).isFalse();
        assertThat(s.get("country").get("chosen").asBoolean()).isFalse();

        addCv();
        s = call(get("/api/onboarding"), null, 200);
        assertThat(s.get("cv").get("present").asBoolean()).isTrue();
        assertThat(s.get("cv").get("entries").asInt()).isEqualTo(1);
        assertThat(s.get("nextStep").asText()).isEqualTo("CLAUDE");

        call(put("/api/claude/connection/api-key"), "{\"key\":\"" + KEY + "\"}", 200);
        call(put("/api/claude/connection/provider"), "{\"id\":\"api\"}", 200);
        assertThat(call(get("/api/onboarding"), null, 200).get("nextStep").asText()).as("chosen but not yet tested").isEqualTo("CLAUDE");

        claudeAnswers("{\"status\":\"ok\"}");
        JsonNode test = call(post("/api/claude/connection/test"), "{\"provider\":\"api\"}", 200);
        assertThat(test.get("ok").asBoolean()).as(test.toString()).isTrue();
        s = call(get("/api/onboarding"), null, 200);
        assertThat(s.get("claude").get("works").asBoolean()).isTrue();
        assertThat(s.get("nextStep").asText()).isEqualTo("COUNTRY");

        call(put("/api/country"), "{\"code\":\"SE\"}", 200);
        assertThat(call(get("/api/onboarding"), null, 200).get("nextStep").asText()).isEqualTo("MODE");

        call(post("/api/onboarding/complete"), null, 409);
        call(put("/api/onboarding/mode"), "{\"mode\":\"DISCOVER\"}", 200);
        s = call(get("/api/onboarding"), null, 200);
        assertThat(s.get("nextStep").isNull()).isTrue();
        assertThat(s.get("mode").get("mode").asText()).isEqualTo("DISCOVER");
        assertThat(s.get("complete").asBoolean()).as("not complete until the user finishes").isFalse();

        assertThat(call(post("/api/onboarding/complete"), null, 200).get("complete").asBoolean()).isTrue();
        assertThat(call(get("/api/onboarding"), null, 200).get("complete").asBoolean()).as("a returning user stays set up").isTrue();
    }

    @Test
    void aFailingConnectionTestKeepsTheClaudeStepOpenWithAHelpfulMessage() throws Exception {
        addCv();
        call(put("/api/claude/connection/api-key"), "{\"key\":\"" + KEY + "\"}", 200);
        call(put("/api/claude/connection/provider"), "{\"id\":\"api\"}", 200);
        anthropic.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/v1/messages")).willReturn(aResponse().withStatus(401)
                .withBody("{\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}")));

        JsonNode test = call(post("/api/claude/connection/test"), "{\"provider\":\"api\"}", 200);

        assertThat(test.get("ok").asBoolean()).isFalse();
        assertThat(test.get("problem").asText()).isEqualTo("INVALID_KEY");
        assertThat(test.get("message").asText()).contains("rejected the saved key");
        assertThat(test.toString()).doesNotContain(KEY);
        assertThat(call(get("/api/onboarding"), null, 200).get("nextStep").asText()).isEqualTo("CLAUDE");
    }

    @Test
    void changingTheKeyOrTheProviderAfterAPassedTestRequiresTestingAgain() throws Exception {
        addCv();
        call(put("/api/claude/connection/api-key"), "{\"key\":\"" + KEY + "\"}", 200);
        call(put("/api/claude/connection/provider"), "{\"id\":\"api\"}", 200);
        claudeAnswers("{\"status\":\"ok\"}");
        call(post("/api/claude/connection/test"), "{\"provider\":\"api\"}", 200);
        assertThat(call(get("/api/onboarding"), null, 200).get("claude").get("works").asBoolean()).isTrue();

        call(put("/api/claude/connection/api-key"), "{\"key\":\"" + KEY.replace("0123", "9999") + "\"}", 200);
        assertThat(call(get("/api/onboarding"), null, 200).get("claude").get("works").asBoolean()).isFalse();

        call(post("/api/claude/connection/test"), "{\"provider\":\"api\"}", 200);
        call(delete("/api/claude/connection/api-key"), null, 200);
        JsonNode s = call(get("/api/onboarding"), null, 200);
        assertThat(s.get("claude").get("works").asBoolean()).isFalse();
        assertThat(s.get("claude").get("provider").asText()).isEqualTo("cli");
    }

    @Test
    void setupCanBeReopenedFromSettingsWithoutLosingAnything() throws Exception {
        addCv();
        call(put("/api/claude/connection/api-key"), "{\"key\":\"" + KEY + "\"}", 200);
        call(put("/api/claude/connection/provider"), "{\"id\":\"api\"}", 200);
        claudeAnswers("{\"status\":\"ok\"}");
        call(post("/api/claude/connection/test"), "{\"provider\":\"api\"}", 200);
        call(put("/api/country"), "{\"code\":\"SE\"}", 200);
        call(put("/api/onboarding/mode"), "{\"mode\":\"PROMPT\"}", 200);
        call(post("/api/onboarding/complete"), null, 200);

        JsonNode reopened = call(post("/api/onboarding/reopen"), null, 200);

        assertThat(reopened.get("complete").asBoolean()).isFalse();
        assertThat(reopened.get("cv").get("entries").asInt()).isEqualTo(1);
        assertThat(reopened.get("nextStep").isNull()).isTrue();
        call(put("/api/onboarding/mode"), "{\"mode\":\"nonsense\"}", 400);
    }
}
