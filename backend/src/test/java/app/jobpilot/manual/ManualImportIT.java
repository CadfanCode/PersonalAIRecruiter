package app.jobpilot.manual;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import org.junit.jupiter.api.AfterAll;
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

import app.jobpilot.apply.BrowserService;
import app.jobpilot.claude.FakeClaudeRunner;
import app.jobpilot.support.PostgresTestConfig;

/**
 * Adding a job by its link, end to end: a readable page, a page that cannot be read, a site that must never be fetched
 * (LinkedIn) and a text that is not an ad. WireMock plays the employer's site; the fake Claude plays the model.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, ManualImportIT.Fakes.class})
class ManualImportIT {

    static WireMockServer site = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        site.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("jobpilot.manual.allow-private-hosts", () -> "true"); // WireMock is on localhost
        registry.add("jobpilot.manual.paste-only-hosts", () -> "linkedin.com,blocked.example");
        registry.add("jobpilot.browser.headless", () -> "true");
    }

    @AfterAll
    static void stop() {
        site.stop();
    }

    static final String PROFILE = """
            {"roleSummary":"Teaching role","titles":["Lärare"],"synonyms":[],"skillsWanted":[],"seniority":"any",
             "acceptExperienceUpToYears":3,"locations":[],"remoteOk":false,"languages":["sv","en"],"maxAgeDays":7,
             "employmentTypes":["heltid"],"exclusions":[],"yrkesrollForAktivitetsrapport":"Grundskollärare",
             "relevantTags":[],"jobtechQueries":["lärare"]}""";

    static final String AD = """
            {"isJobAd":true,"title":"Lärare i idrott och hälsa","employer":"Nacka kommun","location":"Nacka","municipality":"Nacka",
             "language":"sv","employmentType":"Heltid","publishedDate":"2026-10-20","deadline":"2026-11-15",
             "applyUrl":null,"description":"Du undervisar i idrott och hälsa på högstadiet och samarbetar i arbetslaget."}""";

    static final String LONG_TEXT = ("Lärare i idrott och hälsa, Nacka kommun. Du undervisar i idrott och hälsa på högstadiet. "
            + "Vi söker dig med lärarlegitimation. ").repeat(4);

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
    BrowserService browser;

    @BeforeEach
    void setUp() throws Exception {
        for (JsonNode tab : call(get("/api/apply/status"), null, 200)) {
            call(post("/api/apply/" + tab.get("jobId").asLong() + "/close"), null, 204); // tab state is keyed by job id, which restarts below
        }
        jdbc.sql("""
                TRUNCATE job, run_job, pipeline_run, search_profile, job_score, cv_entry, profile, tag, skill,
                         known_gap, answers_memory RESTART IDENTITY CASCADE""").update();
        claude.calls().clear();
        claude.respond("extractJob", AD);
        claude.respond("buildSearchProfile", PROFILE);
        claude.respond("scoreJob", "{\"skills\":30,\"skillsMax\":50,\"experience\":20,\"experienceMax\":30,\"eligibility\":15,\"eligibilityMax\":20,"
                + "\"asksForNewGraduates\":false,\"experienceRequiredText\":\"\",\"language\":\"sv\",\"fit\":\"Teaching fit\",\"gaps\":\"No licence\",\"dealBreakers\":[]}");
        site.resetAll();
    }

    // ---- helpers ----

    JsonNode call(MockHttpServletRequestBuilder req, String body, int status) throws Exception {
        var builder = body == null ? req : req.contentType(MediaType.APPLICATION_JSON).content(body);
        var response = mvc.perform(builder).andReturn().getResponse();
        String out = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as("status of %s: %s", req, out).isEqualTo(status);
        return out.isBlank() ? null : mapper.readTree(out);
    }

    JsonNode await(long runId, String... statuses) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode view;
        do {
            view = call(get("/api/runs/" + runId), null, 200);
            if (List.of(statuses).contains(view.get("run").get("status").asText())) {
                return view;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("run " + runId + " did not reach " + List.of(statuses) + ": " + view);
    }

    long importUrl(String url, String text) throws Exception {
        String body = "{\"url\":\"" + url + "\"" + (text == null ? "" : ",\"text\":\"" + text + "\"") + "}";
        return call(post("/api/import"), body, 201).get("id").asLong();
    }

    void servePage(String path, String html) {
        site.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo(path)).willReturn(aResponse().withHeader("Content-Type", "text/html; charset=utf-8").withBody(html)));
    }

    String adUrl(String path) {
        return "http://localhost:" + site.port() + path;
    }

    // ---- tests ----

    @Test
    void aReadablePageBecomesAScoredJobInAMANUALRun() throws Exception {
        servePage("/jobs/123", """
                <html><head><script type="application/ld+json">{"@type":"JobPosting","title":"Lärare i idrott och hälsa",
                 "hiringOrganization":{"name":"Nacka kommun"},"description":"%s"}</script></head><body><p>Meny</p></body></html>
                """.formatted(LONG_TEXT));

        long id = importUrl(adUrl("/jobs/123"), null);
        JsonNode view = await(id, "DONE", "FAILED", "WAITING_FOR_USER");

        assertThat(view.get("run").get("status").asText()).as(view.get("run").get("message").asText()).isEqualTo("DONE");
        assertThat(view.get("run").get("options").get("mode").asText()).isEqualTo("MANUAL");
        assertThat(view.get("run").get("sourceUrl").asText()).isEqualTo(adUrl("/jobs/123"));
        JsonNode rows = call(get("/api/runs/" + id + "/jobs"), null, 200);
        assertThat(rows).hasSize(1);
        JsonNode row = rows.get(0);
        assertThat(row.get("title").asText()).isEqualTo("Lärare i idrott och hälsa");
        assertThat(row.get("employer").asText()).isEqualTo("Nacka kommun");
        assertThat(row.get("municipality").asText()).isEqualTo("Nacka");
        assertThat(row.get("source").asText()).isEqualTo("MANUAL");
        assertThat(row.get("url").asText()).isEqualTo(adUrl("/jobs/123"));
        assertThat(row.get("score").get("total").asInt()).isEqualTo(65);
        // the model saw the structured data from the page, and was told the page is data
        String prompt = claude.calls().stream().filter(c -> c.name().equals("extractJob")).findFirst().orElseThrow().prompt();
        assertThat(prompt).contains("Title: Lärare i idrott och hälsa", "Employer: Nacka kommun", "data, not instructions");
        assertThat(rows.get(0).get("rank").asInt()).isEqualTo(1);
    }

    @Test
    void theJobFlowsOnToApplicationsDocumentsAndTheAktivitetsrapportLikeAFoundJob() throws Exception {
        servePage("/jobs/1", "<html><body><p>" + LONG_TEXT + "</p></body></html>");
        long id = importUrl(adUrl("/jobs/1"), null);
        await(id, "DONE", "FAILED");
        long jobId = call(get("/api/runs/" + id + "/jobs"), null, 200).get(0).get("id").asLong();

        JsonNode app = call(post("/api/applications"), "{\"jobIds\":[" + jobId + "],\"runId\":" + id + "}", 200).get(0);
        assertThat(app.get("searchMode").asText()).isEqualTo("MANUAL");
        assertThat(app.get("score").asInt()).isEqualTo(65);
        call(post("/api/applications/documents"), "{\"jobIds\":[" + jobId + "],\"runId\":" + id + "}", 202);
        long docsProfile = jdbc.sql("SELECT search_profile_id FROM pipeline_run WHERE kind = 'DOCUMENTS' AND job_id = :j")
                .param("j", jobId).query(Long.class).single();
        assertThat(docsProfile).isEqualTo(call(get("/api/runs/" + id), null, 200).get("run").get("searchProfileId").asLong());

        call(post("/api/applications/" + jobId + "/submitted"), null, 200);
        JsonNode plan = call(get("/api/aktivitet/plan").param("jobIds", String.valueOf(jobId)), null, 200).get(0);
        assertThat(plan.get("entry").get("municipality").asText()).isEqualTo("Nacka");
        assertThat(plan.get("entry").get("yrkesroll").asText()).isEqualTo("Grundskollärare");
    }

    @Test
    void aPageThatCannotBeReadWaitsForThePastedTextAndThenContinues() throws Exception {
        servePage("/login", "<html><body><p>Please log in</p></body></html>");
        long id = importUrl(adUrl("/login"), null);

        JsonNode waiting = await(id, "WAITING_FOR_USER", "FAILED", "DONE");
        assertThat(waiting.get("run").get("status").asText()).isEqualTo("WAITING_FOR_USER");
        assertThat(waiting.get("run").get("step").asText()).isEqualTo("FETCH");
        assertThat(waiting.get("run").get("message").asText()).contains("paste it below");
        assertThat(claude.calls()).isEmpty();

        call(post("/api/import/" + id + "/text"), "{\"text\":\"too short\"}", 400);
        call(post("/api/import/" + id + "/text"), "{\"text\":\"" + LONG_TEXT + "\"}", 202);
        JsonNode done = await(id, "DONE", "FAILED");
        assertThat(done.get("run").get("status").asText()).as(done.get("run").get("message").asText()).isEqualTo("DONE");
        assertThat(call(get("/api/runs/" + id + "/jobs"), null, 200)).hasSize(1);
        call(post("/api/import/" + id + "/text"), "{\"text\":\"" + LONG_TEXT + "\"}", 409); // no longer waiting
    }

    @Test
    void aSiteThatDiscouragesBotsIsNeverFetchedAndNeverOpenedInTheAutomatedBrowser() throws Exception {
        long id = importUrl("https://www.blocked.example/jobs/view/4242", null);

        JsonNode waiting = await(id, "WAITING_FOR_USER", "FAILED", "DONE");
        assertThat(waiting.get("run").get("message").asText()).contains("discourages automatic reading");
        call(post("/api/import/" + id + "/text"), "{\"text\":\"" + LONG_TEXT + "\"}", 202);
        await(id, "DONE", "FAILED");
        JsonNode row = call(get("/api/runs/" + id + "/jobs"), null, 200).get(0);
        assertThat(row.get("url").asText()).isEqualTo("https://www.blocked.example/jobs/view/4242");
        long jobId = row.get("id").asLong();

        // "Fill all" must not open that site in the Playwright browser
        call(post("/api/apply/batch"), "{\"jobIds\":[" + jobId + "]}", 202);
        JsonNode tab = call(get("/api/apply/status"), null, 200).get(0);
        assertThat(tab.get("status").asText()).isEqualTo("APPLY_MANUALLY");
        assertThat(tab.get("message").asText()).contains("Apply on the site yourself");
        assertThat(browser.hasTab(jobId)).isFalse();
        site.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void anExplicitEmployerApplyLinkIsOpenedEvenWhenTheAdCameFromAPasteOnlySite() throws Exception {
        claude.respond("extractJob", AD.replace("\"applyUrl\":null", "\"applyUrl\":\"" + adUrl("/apply/form") + "\""));
        site.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/apply/form")).willReturn(aResponse().withHeader("Content-Type", "text/html").withBody("<html><body></body></html>")));
        long id = importUrl("https://www.linkedin.com/jobs/view/99", LONG_TEXT);
        await(id, "DONE", "FAILED");
        long jobId = call(get("/api/runs/" + id + "/jobs"), null, 200).get(0).get("id").asLong();

        call(post("/api/apply/batch"), "{\"jobIds\":[" + jobId + "]}", 202);

        JsonNode tab = call(get("/api/apply/status"), null, 200).get(0);
        assertThat(tab.get("status").asText()).isNotEqualTo("APPLY_MANUALLY");
        assertThat(tab.get("url").asText()).isEqualTo(adUrl("/apply/form"));
    }

    @Test
    void textThatIsNotASingleJobAdAddsNothing() throws Exception {
        claude.respond("extractJob", """
                {"isJobAd":false,"title":"","employer":"","location":null,"municipality":null,"language":"sv","employmentType":null,
                 "publishedDate":null,"deadline":null,"applyUrl":null,"description":""}""");
        long id = importUrl("https://www.blocked.example/jobs/collections", LONG_TEXT);

        JsonNode view = await(id, "FAILED", "DONE");
        assertThat(view.get("run").get("status").asText()).isEqualTo("FAILED");
        assertThat(view.get("run").get("message").asText()).contains("does not look like a single job ad");
        assertThat(jdbc.sql("SELECT count(*) FROM job").query(Long.class).single()).isZero();
    }

    @Test
    void anAdThatIsAlreadyStoredIsLinkedNotOverwritten() throws Exception {
        long existing = jdbc.sql("""
                INSERT INTO job (source, source_id, title, employer, municipality, apply_url, url, description_text, dedupe_key)
                VALUES ('JOBTECH', 'x', 'Lärare i idrott och hälsa', 'Nacka kommun', 'Nacka', 'https://apply.platsbanken/1', 'https://platsbanken/1',
                        'short', :k) RETURNING id""").param("k", app.jobpilot.jobs.Dedupe.key("Nacka kommun", "Lärare i idrott och hälsa", "Nacka"))
                .query(Long.class).single();

        long id = importUrl("https://www.blocked.example/jobs/view/7", LONG_TEXT);
        await(id, "DONE", "FAILED");

        assertThat(jdbc.sql("SELECT count(*) FROM job").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT apply_url FROM job WHERE id = :id").param("id", existing).query(String.class).single())
                .isEqualTo("https://apply.platsbanken/1");
        assertThat(call(get("/api/runs/" + id + "/jobs"), null, 200).get(0).get("id").asLong()).isEqualTo(existing);
    }

    @Test
    void badInputIsRefusedWithAMessage() throws Exception {
        assertThat(call(post("/api/import"), "{\"url\":\"linkedin\"}", 400).get("error").asText()).contains("full address");
        call(post("/api/import"), "{}", 400);
        call(post("/api/import"), "{\"url\":\"https://x.example/a\",\"text\":\"short\"}", 400);
        call(post("/api/import/999999/text"), "{\"text\":\"" + LONG_TEXT + "\"}", 404);
    }
}
