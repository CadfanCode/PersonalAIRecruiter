package se.caibirch.jobpilot.country;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import se.caibirch.jobpilot.claude.ClaudeConcurrency;
import se.caibirch.jobpilot.support.PostgresTestConfig;

/** The country choice (Sweden only for now) and the adjustable model-call limit, both kept in app_setting. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class CountryAndPerformanceIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    ClaudeConcurrency concurrency;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM app_setting").update();
        concurrency.setLimit(4);
        jdbc.sql("DELETE FROM app_setting").update();
    }

    JsonNode call(MockHttpServletRequestBuilder request, String body, int status) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        String text = mvc.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(status))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isBlank() ? null : mapper.readTree(text);
    }

    @Test
    void swedenIsTheDefaultAndTheOnlyCountryThatCanBeChosenForNow() throws Exception {
        JsonNode view = call(get("/api/country"), null, 200);
        assertThat(view.get("selected").asText()).isEqualTo("SE");
        assertThat(view.get("chosen").asBoolean()).isFalse();
        assertThat(view.get("activityReport").asBoolean()).isTrue();
        for (JsonNode c : view.get("countries")) {
            assertThat(c.get("available").asBoolean()).as(c.get("code").asText()).isEqualTo(c.get("code").asText().equals("SE"));
        }

        JsonNode soon = call(put("/api/country"), "{\"code\":\"NO\"}", 409);
        assertThat(soon.get("error").asText()).contains("coming soon");
        call(put("/api/country"), "{\"code\":\"XX\"}", 400);

        JsonNode chosen = call(put("/api/country"), "{\"code\":\"SE\"}", 200);
        assertThat(chosen.get("chosen").asBoolean()).isTrue();
    }

    @Test
    void theModelCallLimitIsAdjustableWithinItsRangeAndAppliesAtOnce() throws Exception {
        assertThat(call(get("/api/settings/performance"), null, 200).get("claudeConcurrency").asInt()).isEqualTo(4);

        JsonNode changed = call(put("/api/settings/performance"), "{\"claudeConcurrency\":6}", 200);
        assertThat(changed.get("claudeConcurrency").asInt()).isEqualTo(6);
        assertThat(concurrency.limit()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT value FROM app_setting WHERE key = 'claude.concurrency'").query(String.class).single()).isEqualTo("6");

        call(put("/api/settings/performance"), "{\"claudeConcurrency\":0}", 400);
        call(put("/api/settings/performance"), "{\"claudeConcurrency\":99}", 400);
        assertThat(concurrency.limit()).isEqualTo(6);
    }
}
