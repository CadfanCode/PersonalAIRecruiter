package app.jobpilot.onboarding;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import app.jobpilot.claude.ClaudeCallException;
import app.jobpilot.claude.FakeClaudeRunner;
import app.jobpilot.support.PostgresTestConfig;

/** First-run setup from an empty install to the pipeline, with the API provider answering from a local stand-in server. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, OnboardingIT.Fakes.class})
class OnboardingIT {

    static java.nio.file.Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws java.io.IOException {
        dataDir = java.nio.file.Files.createTempDirectory("jobpilot-onboarding");
        registry.add("jobpilot.data-dir", () -> dataDir.toString());
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeClaudeRunner fakeRunner() {
            return new FakeClaudeRunner();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    FakeClaudeRunner claude;

    @BeforeEach
    void emptyInstall() {
        jdbc.sql("TRUNCATE cv_entry, profile, tag, skill, known_gap, answers_memory RESTART IDENTITY CASCADE").update();
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

    void claudeAnswers(String json) {
        claude.respond("health", json);
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

        call(put("/api/claude/connection/provider"), "{\"id\":\"cli\"}", 200);
        assertThat(call(get("/api/onboarding"), null, 200).get("nextStep").asText()).as("chosen but not yet tested").isEqualTo("CLAUDE");

        claudeAnswers("{\"status\":\"ok\"}");
        JsonNode test = call(post("/api/claude/connection/test"), null, 200);
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
        call(put("/api/claude/connection/provider"), "{\"id\":\"cli\"}", 200);
        claude.respond("health", t -> {
            throw new ClaudeCallException("Cannot start 'claude': error=2, No such file");
        });

        JsonNode test = call(post("/api/claude/connection/test"), null, 200);

        assertThat(test.get("ok").asBoolean()).isFalse();
        assertThat(test.get("problem").asText()).isEqualTo("NOT_INSTALLED");
        assertThat(call(get("/api/onboarding"), null, 200).get("nextStep").asText()).isEqualTo("CLAUDE");
    }

    @Test
    void changingTheProviderSettingAfterAPassedTestRequiresTestingAgain() throws Exception {
        addCv();
        claudeAnswers("{\"status\":\"ok\"}");
        call(post("/api/claude/connection/test"), null, 200);
        assertThat(call(get("/api/onboarding"), null, 200).get("claude").get("works").asBoolean()).isTrue();

        call(put("/api/claude/connection/provider"), "{\"id\":\"cli\"}", 200);

        JsonNode s = call(get("/api/onboarding"), null, 200);
        assertThat(s.get("claude").get("works").asBoolean()).isFalse();
        assertThat(s.get("claude").get("provider").asText()).isEqualTo("cli");
    }

    @Test
    void setupCanBeReopenedFromSettingsWithoutLosingAnything() throws Exception {
        addCv();
        claudeAnswers("{\"status\":\"ok\"}");
        call(post("/api/claude/connection/test"), null, 200);
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
