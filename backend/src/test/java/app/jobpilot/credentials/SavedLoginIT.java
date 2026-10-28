package app.jobpilot.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

/** Settings, Saved logins: the password goes into the credential store only and is never returned or indexed. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, SavedLoginIT.Fakes.class})
class SavedLoginIT {

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
    SavedLoginService service;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM saved_login").update();
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
    void aLoginIsStoredByHostWithThePasswordOnlyInTheCredentialStore() throws Exception {
        JsonNode saved = call(put("/api/logins"), "{\"host\":\"https://Jobs.Example.com:8443/login?x=1\",\"username\":\" cai@example.com \",\"password\":\"p@ss w0rd\"}", 200);

        assertThat(saved.get("host").asText()).isEqualTo("jobs.example.com");
        assertThat(saved.get("username").asText()).isEqualTo("cai@example.com");
        assertThat(saved.toString()).doesNotContain("p@ss");
        assertThat(secrets.contents()).containsEntry("portal:jobs.example.com", "p@ss w0rd");
        assertThat(call(get("/api/logins"), null, 200).toString()).doesNotContain("p@ss");
        assertThat(jdbc.sql("SELECT host || '|' || username FROM saved_login").query(String.class).single()).isEqualTo("jobs.example.com|cai@example.com");
    }

    @Test
    void savingAgainReplacesTheLoginAndDeletingRemovesItEverywhere() throws Exception {
        call(put("/api/logins"), "{\"host\":\"jobs.example.com\",\"username\":\"a@example.com\",\"password\":\"one\"}", 200);
        call(put("/api/logins"), "{\"host\":\"jobs.example.com\",\"username\":\"b@example.com\",\"password\":\"two\"}", 200);
        assertThat(call(get("/api/logins"), null, 200)).hasSize(1);
        assertThat(service.credentialsFor("jobs.example.com").orElseThrow().password()).isEqualTo("two");

        call(delete("/api/logins/jobs.example.com"), null, 204);
        assertThat(call(get("/api/logins"), null, 200)).isEmpty();
        assertThat(secrets.contents()).isEmpty();
        call(delete("/api/logins/jobs.example.com"), null, 404);
    }

    @Test
    void aLoginIsFoundForExactlyItsHostAndNoOtherHost() {
        service.save("jobs.example.com", "a@example.com", "pw");

        assertThat(service.credentialsFor("jobs.example.com")).isPresent();
        assertThat(service.credentialsFor("JOBS.EXAMPLE.COM")).isPresent();
        assertThat(service.credentialsFor("www.jobs.example.com")).isEmpty();
        assertThat(service.credentialsFor("example.com")).isEmpty();
        assertThat(service.credentialsFor("evil-jobs.example.com")).isEmpty();
        assertThat(service.credentialsFor(null)).isEmpty();
    }

    @Test
    void badInputIsRefusedAndNothingIsKept() throws Exception {
        call(put("/api/logins"), "{\"host\":\"not a host\",\"username\":\"a\",\"password\":\"x\"}", 400);
        call(put("/api/logins"), "{\"host\":\"jobs.example.com\",\"username\":\" \",\"password\":\"x\"}", 400);
        call(put("/api/logins"), "{\"host\":\"jobs.example.com\",\"username\":\"a\",\"password\":\"\"}", 400);
        assertThat(secrets.contents()).isEmpty();
        assertThat(call(get("/api/logins"), null, 200)).isEmpty();
    }

    @Test
    void withoutACredentialStoreNothingIsSavedOrIndexed() throws Exception {
        secrets.setAvailable(false);

        JsonNode refused = call(put("/api/logins"), "{\"host\":\"jobs.example.com\",\"username\":\"a\",\"password\":\"pw\"}", 409);

        assertThat(refused.get("error").asText()).contains("secure credential store");
        assertThat(call(get("/api/logins"), null, 200)).isEmpty();
    }

    @Test
    void theCredentialsRecordNeverPrintsThePassword() {
        assertThat(new SavedLoginService.Credentials("h", "u", "hunter2").toString()).doesNotContain("hunter2");
        assertThat(new SavedLoginController.LoginInput("h", "u", "hunter2").toString()).doesNotContain("hunter2");
        assertThatThrownBy(() -> SavedLoginService.normaliseHost("http://")).isInstanceOf(IllegalArgumentException.class);
    }
}
