package se.caibirch.jobpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import se.caibirch.jobpilot.claude.ClaudeCallLog;
import se.caibirch.jobpilot.claude.ClaudeRunner;
import se.caibirch.jobpilot.claude.FakeClaudeRunner;
import se.caibirch.jobpilot.support.PostgresTestConfig;

/** Flyway V1 against real PostgreSQL, plus the Claude endpoints with a fake runner. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class ApiIT {

    static Path versionStub;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        versionStub = Files.createTempFile("claude-version", ".sh");
        Files.writeString(versionStub, "#!/bin/bash\necho '9.9.9 (Claude Code)'\n");
        Files.setPosixFilePermissions(versionStub, PosixFilePermissions.fromString("rwxr-xr-x"));
        registry.add("jobpilot.claude.path", versionStub::toString);
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
    ClaudeCallLog callLog;

    @Test
    void flywayCreatesTheWholeSchema() {
        List<String> tables = jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
                .query(String.class).list();
        assertThat(tables).contains("profile", "cv_entry", "cv_fact", "skill", "fact_skill", "tag", "entry_tag",
                "fact_tag", "style_rule", "search_profile", "pipeline_run", "job", "job_score", "document",
                "application_form_snapshot", "aktivitet_log", "claude_call");
    }

    @Test
    void badInputGetsAJsonErrorNotAnInternalServerError() throws Exception {
        mvc.perform(get("/api/runs/abc/jobs")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/runs/1/jobs").param("status", "NOPE")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/jobs/999999")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").exists());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/applications")
                .contentType("application/json").content("{not json")).andExpect(status().isBadRequest());
    }

    @Test
    void jobDedupeKeyIsUnique() {
        jdbc.sql("INSERT INTO job (source, title, dedupe_key) VALUES ('JOBTECH', 'Dev', 'acme|dev|stockholm')").update();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.sql("INSERT INTO job (source, title, dedupe_key) VALUES ('WEB', 'Dev', 'acme|dev|stockholm')").update())
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void healthEndpointReportsOk() throws Exception {
        mvc.perform(get("/api/claude/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.version").value("9.9.9 (Claude Code)"));
    }

    @Test
    void usageEndpointSumsLoggedCalls() throws Exception {
        callLog.record(new ClaudeCallLog.Entry("scoreJob", "m", 100, new java.math.BigDecimal("0.25"), 1L, 2L, 3L, 4L, true, null));
        mvc.perform(get("/api/claude/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.calls").isNumber())
                .andExpect(jsonPath("$.byTask[?(@.task == 'scoreJob')].calls").isNotEmpty());
    }

    @Test
    void foreignOriginIsRefused() throws Exception {
        mvc.perform(get("/api/claude/health").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/claude/health").header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk());
    }
}
