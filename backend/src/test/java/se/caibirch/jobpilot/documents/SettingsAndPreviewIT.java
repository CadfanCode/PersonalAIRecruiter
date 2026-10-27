package se.caibirch.jobpilot.documents;

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

import se.caibirch.jobpilot.claude.FakeClaudeRunner;
import se.caibirch.jobpilot.support.PostgresTestConfig;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, SettingsAndPreviewIT.Fakes.class})
class SettingsAndPreviewIT {

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
    @Autowired
    StyleRuleRepository styleRules;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE search_profile, cv_entry, profile, tag, skill RESTART IDENTITY CASCADE").update();
        jdbc.sql("UPDATE style_rule SET enabled = TRUE").update();
        jdbc.sql("DELETE FROM banned_phrase WHERE phrase LIKE 'TEST%'").update();
        jdbc.sql("UPDATE banned_phrase SET enabled = TRUE").update();
        claude.calls().clear();
    }

    JsonNode call(MockHttpServletRequestBuilder req, String body, int status) throws Exception {
        var builder = body == null ? req : req.contentType(MediaType.APPLICATION_JSON).content(body);
        var response = mvc.perform(builder).andReturn().getResponse();
        String out = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as("status of %s: %s", req, out).isEqualTo(status);
        return out.isBlank() ? null : mapper.readTree(out);
    }

    @Test
    void theSevenWritingRulesFromClaudeMdAreSeededAndEditable() throws Exception {
        JsonNode rules = call(get("/api/settings/style-rules"), null, 200);
        assertThat(rules).hasSize(7);
        assertThat(rules.toString()).contains("no_dashes", "no_new_grad", "letter_shape", "only_cv_facts");

        long dashes = rules.get(1).get("id").asLong();
        call(put("/api/settings/style-rules/" + dashes), "{\"ruleText\":\"Use no dashes at all.\",\"enabled\":false}", 200);

        assertThat(styleRules.rulesText()).doesNotContain("Use no dashes at all.").doesNotContain("em-dash");
        call(put("/api/settings/style-rules/" + dashes), "{\"ruleText\":\"Use no dashes at all.\",\"enabled\":true}", 200);
        assertThat(styleRules.rulesText()).contains("- Use no dashes at all.");
        call(put("/api/settings/style-rules/" + dashes), "{\"ruleText\":\"  \"}", 400);
        call(put("/api/settings/style-rules/9999"), "{\"ruleText\":\"x\"}", 404);
    }

    @Test
    void bannedPhrasesCanBeAddedDisabledAndRemoved() throws Exception {
        assertThat(call(get("/api/settings/banned-phrases"), null, 200).size()).isGreaterThanOrEqualTo(23);
        long id = call(post("/api/settings/banned-phrases"), "{\"language\":\"sv\",\"phrase\":\"TEST fantastisk\"}", 201).get("id").asLong();
        assertThat(styleRules.bannedPhrases("sv")).contains("TEST fantastisk");
        assertThat(styleRules.bannedPhrases("en")).doesNotContain("TEST fantastisk");

        call(put("/api/settings/banned-phrases/" + id), "{\"enabled\":false}", 204);
        assertThat(styleRules.bannedPhrases("sv")).doesNotContain("TEST fantastisk");
        assertThat(call(post("/api/settings/banned-phrases"), "{\"language\":\"sv\",\"phrase\":\"TEST fantastisk\"}", 201).get("enabled").asBoolean())
                .as("adding it again re-enables it").isTrue();

        call(delete("/api/settings/banned-phrases/" + id), null, 204);
        assertThat(styleRules.bannedPhrases("sv")).doesNotContain("TEST fantastisk");
        call(delete("/api/settings/banned-phrases/" + id), null, 404);
        call(post("/api/settings/banned-phrases"), "{\"language\":\"de\",\"phrase\":\"x\"}", 400);
        call(post("/api/settings/banned-phrases"), "{\"language\":\"sv\",\"phrase\":\" \"}", 400);
    }

    @Test
    void previewShowsWhichFactsWouldBeChosenForAPastedAdAndStoresNothing() throws Exception {
        jdbc.sql("INSERT INTO search_profile (prompt, profile_json) VALUES ('java', :j::jsonb)").param("j", """
                {"roleSummary":"Java dev","titles":["Javautvecklare"],"synonyms":[],"skillsWanted":["Java"],"seniority":"junior",
                 "acceptExperienceUpToYears":2,"locations":[],"remoteOk":false,"languages":["sv"],"maxAgeDays":7,"employmentTypes":["heltid"],
                 "exclusions":[],"yrkesrollForAktivitetsrapport":"Systemutvecklare/Programmerare","relevantTags":["java"],"jobtechQueries":["java"]}""").update();
        long entry = call(post("/api/cv/entries"), "{\"kind\":\"WORK\",\"title\":\"Utvecklare\",\"titleEn\":\"Developer\",\"note\":\"SECRET\"}", 201).get("id").asLong();
        long fact = call(post("/api/cv/entries/" + entry + "/facts"), "{\"textEn\":\"Built services.\",\"textSv\":\"Byggde tjänster.\"}", 201).get("id").asLong();
        call(post("/api/cv/entries/" + entry + "/facts"), "{\"textEn\":\"Unused fact.\"}", 201);
        claude.respond("selectFacts", "{\"entries\":[{\"entryId\":" + entry + ",\"factIds\":[" + fact + "],\"use\":\"both\",\"reason\":\"Matches the Java requirement\"}]}");

        JsonNode preview = call(post("/api/cv/preview-selection"), "{\"ad\":\"Vi söker en utvecklare som har erfarenhet av Java och Spring.\"}", 200);

        assertThat(preview.get("language").asText()).isEqualTo("sv");
        JsonNode e = preview.get("entries").get(0);
        assertThat(e.get("title").asText()).isEqualTo("Developer");
        assertThat(e.get("reason").asText()).isEqualTo("Matches the Java requirement");
        assertThat(e.get("facts")).hasSize(1);
        assertThat(e.get("facts").get(0).get("text").asText()).isEqualTo("Byggde tjänster.");
        assertThat(claude.calls().get(0).prompt()).contains("Vi söker en utvecklare").doesNotContain("SECRET");
        assertThat(jdbc.sql("SELECT count(*) FROM job").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM document").query(Long.class).single()).isZero();
    }

    @Test
    void previewNeedsAnAdAndASearchProfile() throws Exception {
        call(post("/api/cv/preview-selection"), "{\"ad\":\"  \"}", 400);
        JsonNode e = call(post("/api/cv/preview-selection"), "{\"ad\":\"text\"}", 400);
        assertThat(e.get("error").asText()).contains("Run a search first");
    }
}
