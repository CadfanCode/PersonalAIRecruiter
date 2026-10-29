package app.jobpilot.applications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import app.jobpilot.pipeline.RetentionService;
import app.jobpilot.support.PostgresTestConfig;

/** What survives (ADR 0024): the user's chosen and submitted work; and what does not: search results and anything deleted. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class RetentionIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    RetentionService retention;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE job, run_job, pipeline_run, search_profile, job_score, submitted_application RESTART IDENTITY CASCADE").update();
    }

    long profile() {
        return jdbc.sql("INSERT INTO search_profile (prompt, profile_json) VALUES ('p', '{}'::jsonb) RETURNING id").query(Long.class).single();
    }

    long run(String status, Long profileId) {
        return jdbc.sql("INSERT INTO pipeline_run (status, step, progress, message, started_at, search_profile_id) VALUES (:s, 'RANK', 50, 'm', now(), :p) RETURNING id")
                .param("s", status).param("p", profileId).query(Long.class).single();
    }

    long job(String title, long runId) {
        long id = jdbc.sql("""
                INSERT INTO job (source, source_id, title, employer, municipality, url, description_text, dedupe_key)
                VALUES ('JOBTECH', :t, :t, 'Employer', 'Nacka', 'https://x/' , 'text', :t) RETURNING id""").param("t", title).query(Long.class).single();
        jdbc.sql("INSERT INTO run_job (run_id, job_id, relevance) VALUES (:r, :j, 1)").param("r", runId).param("j", id).update();
        return id;
    }

    void select(long jobId, long runId) {
        jdbc.sql("INSERT INTO application (job_id, run_id, search_mode) VALUES (:j, :r, 'TARGETED')").param("j", jobId).param("r", runId).update();
    }

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Test
    void searchDataGoesButASelectedApplicationKeepsItsJobRunAndProfile() {
        long sp = profile();
        long finished = run("DONE", sp);
        long kept = job("kept", finished);
        job("scratch", finished);
        select(kept, finished);

        retention.purgeSearchData();

        assertThat(count("job")).isEqualTo(1);
        assertThat(count("pipeline_run")).isEqualTo(1); // the run the application was chosen from stays: it is where its score comes from
        assertThat(count("search_profile")).isEqualTo(1);
        assertThat(count("run_job")).isEqualTo(1);
    }

    @Test
    void finishedRunsWithoutSelectionsAreRemovedWithTheirProfilesButARunningOneIsLeftAlone() {
        long finished = run("DONE", profile());
        job("old", finished);
        long waiting = run("WAITING_FOR_USER", profile());
        job("waiting", waiting);
        long running = run("RUNNING", profile());
        long live = job("live", running);

        retention.purgeSearchData();

        assertThat(jdbc.sql("SELECT id FROM pipeline_run").query(Long.class).list()).containsExactly(running);
        assertThat(jdbc.sql("SELECT id FROM job").query(Long.class).list()).containsExactly(live);
        assertThat(count("search_profile")).isEqualTo(1);
    }

    @Test
    void aSubmittedApplicationAndItsRecordSurviveEverything() {
        long finished = run("DONE", profile());
        long sent = job("sent", finished);
        select(sent, finished);
        jdbc.sql("UPDATE application SET submitted_at = now() WHERE job_id = :j").param("j", sent).update();
        jdbc.sql("INSERT INTO submitted_application (job_id, title, submitted_at) VALUES (:j, 'sent', now())").param("j", sent).update();

        retention.purgeSearchData();

        assertThat(count("job")).isEqualTo(1);
        assertThat(count("application")).isEqualTo(1);
        assertThat(count("submitted_application")).isEqualTo(1);
    }

    @Test
    void deletingAnApplicationRemovesItsJobDocumentsAndFilesForGood() throws Exception {
        long finished = run("DONE", profile());
        long doomed = job("doomed", finished);
        long other = job("other", finished);
        select(doomed, finished);
        select(other, finished);
        Path pdf = Files.createTempFile("cv", ".pdf");
        jdbc.sql("""
                INSERT INTO document (job_id, kind, language, content_json, pdf_path) VALUES (:j, 'CV', 'sv', '{}'::jsonb, :p)""")
                .param("j", doomed).param("p", pdf.toString()).update();

        var response = mvc.perform(post("/api/applications/delete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobIds\":[" + doomed + "]}")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("\"deleted\":1");
        assertThat(jdbc.sql("SELECT id FROM job").query(Long.class).list()).containsExactly(other);
        assertThat(count("document")).isZero();
        assertThat(Files.exists(pdf)).isFalse();
    }

    @Test
    void aSubmittedApplicationCannotBeDeletedAndNothingElseIsDeletedWithIt() throws Exception {
        long finished = run("DONE", profile());
        long sent = job("sent", finished);
        long draft = job("draft", finished);
        select(sent, finished);
        select(draft, finished);
        jdbc.sql("UPDATE application SET submitted_at = now() WHERE job_id = :j").param("j", sent).update();

        var response = mvc.perform(post("/api/applications/delete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobIds\":[" + draft + "," + sent + "]}")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(count("job")).isEqualTo(2);
    }
}
