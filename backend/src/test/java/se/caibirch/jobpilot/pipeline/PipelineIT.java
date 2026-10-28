package se.caibirch.jobpilot.pipeline;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
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

import se.caibirch.jobpilot.claude.ClaudeCallException;
import se.caibirch.jobpilot.claude.FakeClaudeRunner;
import se.caibirch.jobpilot.support.PostgresTestConfig;

/**
 * The whole pipeline against real PostgreSQL, a WireMock JobTech and taxonomy server and a fake
 * Claude. Nothing reaches the live APIs or the real CLI.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, PipelineIT.Fakes.class})
class PipelineIT {

    static WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        wm.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("jobpilot.jobtech.base-url", wm::baseUrl);
        registry.add("jobpilot.jobtech.taxonomy-url", wm::baseUrl);
    }

    @AfterAll
    static void stop() {
        wm.stop();
    }

    static final String PROFILE = """
            {"roleSummary":"Junior Java developer","titles":["Javautvecklare"],"synonyms":[],"skillsWanted":["Java"],
             "seniority":"graduate","acceptExperienceUpToYears":2,
             "locations":[{"name":"Stockholm","municipality":"Stockholm","radiusKm":0},
                          {"name":"Stockholm","municipality":"Solna","radiusKm":0},
                          {"name":"Stockholm","municipality":"Järfälla","radiusKm":0}],
             "remoteOk":false,"languages":["sv","en"],"maxAgeDays":7,"employmentTypes":["heltid"],
             "exclusions":["senior"],"yrkesrollForAktivitetsrapport":"Systemutvecklare/Programmerare",
             "relevantTags":["java"],"jobtechQueries":["java utvecklare","backend developer java"]}""";

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
    se.caibirch.jobpilot.settings.JobPilotProperties props;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    FakeClaudeRunner claude;
    @Autowired
    RunRepository runRepository;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE job, run_job, pipeline_run, search_profile, job_score, cv_entry, profile, tag, skill,
                         known_gap, answers_memory RESTART IDENTITY CASCADE""").update();
        claude.calls().clear();
        claude.respond("buildSearchProfile", PROFILE);
        claude.respond("scoreJob", task -> {
            String p = task.prompt();
            if (p.contains("scoreFails")) {
                throw new ClaudeCallException("boom");
            }
            if (p.contains("Title: Junior Javautvecklare")) {
                return score(45, 28, 18, "Strong Java match");
            }
            if (p.contains("Title: Software Developer")) {
                return score(40, 20, 15, "Java and microservices");
            }
            return score(30, 25, 10, "Frontend, not Java");
        });
        wm.resetAll();
        wm.stubFor(WireMock.get(urlPathEqualTo("/v1/taxonomy/main/concepts")).willReturn(json("""
                [{"taxonomy/id":"AvNB_uwa_6n6","taxonomy/preferred-label":"Stockholm"},
                 {"taxonomy/id":"zHxw_uJZ_NJ8","taxonomy/preferred-label":"Solna"},
                 {"taxonomy/id":"qm5H_jsD_fUF","taxonomy/preferred-label":"Järfälla"}]""")));
        stubSearch("java utvecklare", hit("1", "Junior Javautvecklare", "Comstream AB", "Stockholm", 0.9, 5000, false),
                hit("2", "Software Developer", "Saab AB", "Järfälla", 0.8, 5000, false),
                hit("3", "Junior Frontendutvecklare", "Arbetsförmedlingen", "Solna", 0.7, 5000, false),
                hit("4", "Senior Java Developer", "Acme", "Stockholm", 0.6, 5000, false),
                hit("5", "Java dev", "Far Away AB", "Malmö", 0.6, 5000, false),
                hit("6", "Java dev expired", "Late AB", "Stockholm", 0.6, -1, false),
                hit("7", "Java dev removed", "Gone AB", "Stockholm", 0.6, 5000, true));
        stubSearch("backend developer java", hit("2b", "Software Developer", "Saab AB", "Järfälla", 0.5, 5000, false));
    }

    // ---- helpers ----

    static String score(int skills, int exp, int elig, String fit) {
        return "{\"skills\":" + skills + ",\"skillsMax\":50,\"experience\":" + exp + ",\"experienceMax\":30,\"eligibility\":" + elig
                + ",\"eligibilityMax\":20,\"asksForNewGraduates\":false,\"experienceRequiredText\":\"1 år\",\"language\":\"sv\",\"fit\":\""
                + fit + "\",\"gaps\":\"No Angular\",\"dealBreakers\":[]}";
    }

    static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }

    static String hit(String id, String title, String employer, String municipality, double relevance, int deadlineMinutes,
                      boolean removed) {
        ZoneId se = ZoneId.of("Europe/Stockholm");
        String published = LocalDateTime.ofInstant(Instant.now().minusSeconds(86_400), se).withNano(0).toString();
        String deadline = LocalDateTime.ofInstant(Instant.now().plusSeconds(deadlineMinutes * 60L), se).withNano(0).toString();
        return """
                {"id":"%s","headline":"%s","relevance":%s,"removed":%s,"webpage_url":"https://platsbanken/%s",
                 "application_deadline":"%s","publication_date":"%s",
                 "description":{"text":"Vi söker en utvecklare som har erfarenhet av Java och Spring för %s%s"},
                 "employer":{"name":"%s","workplace":"%s"},"working_hours_type":{"label":"Heltid"},
                 "application_details":{"url":"https://apply/%s"},
                 "workplace_address":{"municipality":"%s","city":"%s"}}""".formatted(id, title, relevance, removed, id,
                deadline, published, title, title.startsWith("Junior Java") ? ". Du är i början av din karriär." : "",
                employer, employer, id, municipality, municipality);
    }

    static void stubSearch(String query, String... hits) {
        wm.stubFor(WireMock.get(urlPathEqualTo("/search")).withQueryParam("q", equalTo(query)).willReturn(json(
                "{\"total\":{\"value\":" + hits.length + "},\"hits\":[" + String.join(",", hits) + "]}")));
    }

    JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String body, int status)
            throws Exception {
        var builder = body == null ? req : req.contentType(MediaType.APPLICATION_JSON).content(body);
        var response = mvc.perform(builder).andReturn().getResponse();
        String out = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as("status of %s: %s", req, out).isEqualTo(status);
        return out.isBlank() ? null : mapper.readTree(out);
    }

    long startRun(boolean auto) throws Exception {
        return call(post("/api/runs"), "{\"prompt\":\"new-grad Java Stockholm 7 days\",\"autoContinue\":" + auto + "}", 201)
                .get("id").asLong();
    }

    JsonNode awaitStatus(long runId, String... statuses) throws Exception {
        Set<String> wanted = Set.of(statuses);
        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode view;
        do {
            view = call(get("/api/runs/" + runId), null, 200);
            if (wanted.contains(view.get("run").get("status").asText())) {
                return view;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("Run " + runId + " did not reach " + wanted + ": " + view);
    }

    List<String> titles(JsonNode rows) {
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.get("title").asText()));
        return out;
    }

    // ---- tests ----

    // ---- live progress: the time left is estimated while ads are being scored ----

    long runningRun(String step, int progress, int stepStartedSecondsAgo) {
        return jdbc.sql("""
                INSERT INTO pipeline_run (status, step, progress, message, started_at, step_started_at, options_json)
                VALUES ('RUNNING', :step, :p, 'working', now() - interval '10 minutes', now() - (:ago || ' seconds')::interval, '{}'::jsonb)
                RETURNING id""").param("step", step).param("p", progress).param("ago", String.valueOf(stepStartedSecondsAgo))
                .query(Long.class).single();
    }

    @Test
    void whileAdsAreBeingScoredTheViewEstimatesTheTimeLeftFromTheShareDone() throws Exception {
        // scoring covers progress 45 to 95: at 70 it is half done after 60 s, so about 60 s are left
        long id = runningRun("RANK", 70, 60);

        JsonNode view = call(get("/api/runs/" + id), null, 200);

        assertThat(view.get("etaSeconds").asInt()).isBetween(55, 70);
    }

    @Test
    void noEstimateIsGivenBeforeScoringHasProgressedOrInAnotherStep() throws Exception {
        assertThat(call(get("/api/runs/" + runningRun("RANK", 46, 30)), null, 200).get("etaSeconds").isNull()).as("barely started").isTrue();
        assertThat(call(get("/api/runs/" + runningRun("SEARCH", 30, 30)), null, 200).get("etaSeconds").isNull()).as("searching").isTrue();
        long done = jdbc.sql("""
                INSERT INTO pipeline_run (status, step, progress, message, started_at, options_json)
                VALUES ('DONE', 'DONE', 100, 'done', now(), '{}'::jsonb) RETURNING id""").query(Long.class).single();
        assertThat(call(get("/api/runs/" + done), null, 200).get("etaSeconds").isNull()).isTrue();
    }

    @Test
    void theAdsToScoreDefaultToTwentyAndAreCappedAt150InEveryMode() throws Exception {
        call(post("/api/runs"), "{\"prompt\":\"java\",\"limit\":151}", 400);
        call(post("/api/runs"), "{\"prompt\":\"java\",\"limit\":0}", 400);
        call(post("/api/runs"), "{\"mode\":\"DISCOVER\",\"limit\":151}", 400);
        assertThat(jdbc.sql("SELECT count(*) FROM pipeline_run").query(Long.class).single()).as("nothing was started").isZero();
        assertThat(PipelineService.MAX_LIMIT).isEqualTo(150);
        assertThat(props.search().defaultLimit()).isEqualTo(20);
    }

    @Test
    void autoRunSearchesFiltersDedupesScoresAndRanks() throws Exception {
        long run = startRun(true);

        JsonNode view = awaitStatus(run, "DONE", "FAILED");

        assertThat(view.get("run").get("status").asText()).as(view.get("run").get("message").asText()).isEqualTo("DONE");
        assertThat(view.get("run").get("step").asText()).isEqualTo("DONE");
        assertThat(view.get("profile").get("jobtechQueries")).hasSize(2);
        JsonNode rows = call(get("/api/runs/" + run + "/jobs"), null, 200);
        // senior, Malmö, expired and removed ads are gone; "Software Developer" appears once despite two queries
        assertThat(titles(rows)).containsExactly("Junior Javautvecklare", "Software Developer", "Junior Frontendutvecklare");
        assertThat(rows.get(0).get("rank").asInt()).isEqualTo(1);
        assertThat(rows.get(0).get("score").get("total").asInt()).isEqualTo(91);
        assertThat(rows.get(1).get("score").get("total").asInt()).isEqualTo(75);
        assertThat(rows.get(2).get("municipality").asText()).isEqualTo("Solna");
        assertThat(rows.get(0).get("experienceRequiredText").asText()).isEqualTo("1 år");
        // the scorer said false, but the ad says "i början av din karriär": the shared phrase list wins
        assertThat(rows.get(0).get("asksForNewGraduates").asBoolean()).isTrue();
        assertThat(rows.get(1).get("asksForNewGraduates").asBoolean()).isFalse();
        assertThat(rows.get(0).get("applyUrl").asText()).isEqualTo("https://apply/1");
        assertThat(view.get("run").get("message").asText()).contains("Found 7 ads", "4 after filters",
                "1 excluded term", "1 outside locations", "1 deadline passed", "scoring 3");

        // search used the three resolved municipality ids and the age in minutes
        wm.verify(getRequestedFor(urlPathEqualTo("/search")).withQueryParam("municipality", equalTo("AvNB_uwa_6n6"))
                .withQueryParam("municipality", equalTo("zHxw_uJZ_NJ8")).withQueryParam("municipality", equalTo("qm5H_jsD_fUF"))
                .withQueryParam("published-after", equalTo("10080")));
        assertThat(jdbc.sql("SELECT count(*) FROM job").query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM run_job").query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void runPausesForReviewAndUsesTheEditedProfile() throws Exception {
        long run = startRun(false);
        JsonNode waiting = awaitStatus(run, "WAITING_FOR_USER", "FAILED");
        assertThat(waiting.get("run").get("message").asText()).contains("Review the search profile");
        assertThat(jdbc.sql("SELECT count(*) FROM job").query(Long.class).single()).isZero();

        ObjectNode edited = (ObjectNode) waiting.get("profile").deepCopy();
        edited.putArray("jobtechQueries").add("java utvecklare");
        edited.put("maxAgeDays", 3);
        JsonNode afterEdit = call(put("/api/runs/" + run + "/profile"), edited.toString(), 200);
        assertThat(afterEdit.get("profile").get("jobtechQueries")).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM search_profile").query(Long.class).single()).as("immutable rows").isEqualTo(2);

        edited.put("seniority", "wizard");
        call(put("/api/runs/" + run + "/profile"), edited.toString(), 400);

        call(post("/api/runs/" + run + "/continue"), null, 200);
        awaitStatus(run, "DONE", "FAILED");
        wm.verify(0, getRequestedFor(urlPathEqualTo("/search")).withQueryParam("q", equalTo("backend developer java")));
        wm.verify(getRequestedFor(urlPathEqualTo("/search")).withQueryParam("published-after", equalTo("4320")));
        call(put("/api/runs/" + run + "/profile"), edited.put("seniority", "junior").toString(), 409);
        call(post("/api/runs/" + run + "/continue"), null, 409);
    }

    @Test
    void waitingRunCanBeCancelled() throws Exception {
        long run = startRun(false);
        awaitStatus(run, "WAITING_FOR_USER");

        JsonNode cancelled = call(post("/api/runs/" + run + "/cancel"), null, 200);

        assertThat(cancelled.get("run").get("status").asText()).isEqualTo("CANCELLED");
        call(post("/api/runs/" + run + "/cancel"), null, 409);
    }

    @Test
    void failedProfileCallFailsTheRunWithTheReason() throws Exception {
        claude.respond("buildSearchProfile", task -> {
            throw new ClaudeCallException("Claude CLI exited with code 1");
        });

        JsonNode view = awaitStatus(startRun(true), "FAILED");

        assertThat(view.get("run").get("message").asText()).contains("exited with code 1");
    }

    @Test
    void oneFailedScoreDoesNotSinkTheRun() throws Exception {
        stubSearch("java utvecklare", hit("1", "Junior Javautvecklare", "Comstream AB", "Stockholm", 0.9, 5000, false),
                hit("8", "scoreFails developer", "Broken AB", "Stockholm", 0.8, 5000, false));
        stubSearch("backend developer java");

        long run = startRun(true);
        JsonNode view = awaitStatus(run, "DONE", "FAILED");

        assertThat(view.get("run").get("status").asText()).isEqualTo("DONE");
        assertThat(view.get("run").get("message").asText()).contains("Scored 1").contains("1 failed");
        JsonNode rows = call(get("/api/runs/" + run + "/jobs"), null, 200);
        assertThat(titles(rows)).containsExactly("Junior Javautvecklare", "scoreFails developer");
        assertThat(rows.get(1).get("score").isNull()).isTrue();
        assertThat(rows.get(1).get("rank").isNull()).isTrue();
    }

    @Test
    void searchFailureFailsTheRun() throws Exception {
        wm.stubFor(WireMock.get(urlPathEqualTo("/search")).willReturn(aResponse().withStatus(400)));

        JsonNode view = awaitStatus(startRun(true), "FAILED");

        assertThat(view.get("run").get("message").asText()).contains("Every JobTech query failed");
    }

    @Test
    void limitScoresOnlyTheMostRelevantAds() throws Exception {
        long run = call(post("/api/runs"), "{\"prompt\":\"java\",\"autoContinue\":true,\"limit\":2}", 201).get("id").asLong();

        JsonNode view = awaitStatus(run, "DONE", "FAILED");

        assertThat(view.get("run").get("message").asText()).contains("scoring 2");
        JsonNode rows = call(get("/api/runs/" + run + "/jobs"), null, 200);
        assertThat(rows).hasSize(3);
        assertThat(titles(rows)).containsExactly("Junior Javautvecklare", "Software Developer", "Junior Frontendutvecklare");
        assertThat(rows.get(2).get("score").isNull()).as("lowest relevance is stored but not scored").isTrue();
    }

    @Test
    void scoresAreCachedPerProfileAndCvVersion() throws Exception {
        long run = startRun(true);
        awaitStatus(run, "DONE");
        long scoreCalls = claude.calls().stream().filter(c -> c.name().equals("scoreJob")).count();
        assertThat(scoreCalls).isEqualTo(3);

        long profileId = jdbc.sql("SELECT search_profile_id FROM pipeline_run WHERE id = :id").param("id", run).query(Long.class).single();
        assertThat(jdbc.sql("SELECT count(DISTINCT cv_version) FROM job_score WHERE search_profile_id = :p").param("p", profileId)
                .query(Long.class).single()).isEqualTo(1);

        // Changing the CV changes the version, so the same ads are scored again.
        call(post("/api/cv/entries"), "{\"kind\":\"PROJECT\",\"title\":\"New project\"}", 201);
        JsonNode second = awaitStatus(startRun(true), "DONE");
        assertThat(second.get("run").get("message").asText()).contains("Scored 3");
        assertThat(claude.calls().stream().filter(c -> c.name().equals("scoreJob")).count()).isEqualTo(6);
    }

    @Test
    void jobsKeepTheirStatusAndIdWhenFoundAgain() throws Exception {
        long run1 = startRun(true);
        awaitStatus(run1, "DONE");
        JsonNode first = call(get("/api/runs/" + run1 + "/jobs"), null, 200).get(0);
        long jobId = first.get("id").asLong();
        call(patch("/api/jobs/" + jobId), "{\"status\":\"SHORTLISTED\",\"notes\":\"call them\"}", 204);

        long run2 = startRun(true);
        awaitStatus(run2, "DONE");

        JsonNode again = call(get("/api/runs/" + run2 + "/jobs"), null, 200).get(0);
        assertThat(again.get("id").asLong()).isEqualTo(jobId);
        assertThat(again.get("status").asText()).isEqualTo("SHORTLISTED");
        assertThat(again.get("notes").asText()).isEqualTo("call them");
        assertThat(jdbc.sql("SELECT count(*) FROM job").query(Long.class).single()).isEqualTo(3);
        assertThat(call(get("/api/runs/" + run1 + "/jobs"), null, 200)).hasSize(3);
    }

    @Test
    void aShorterDescriptionNeverReplacesARicherOne() throws Exception {
        awaitStatus(startRun(true), "DONE");
        String before = jdbc.sql("SELECT description_text FROM job WHERE title = 'Junior Javautvecklare'").query(String.class).single();
        String shorter = hit("1", "Junior Javautvecklare", "Comstream AB", "Stockholm", 0.9, 5000, false)
                .replace("Vi söker en utvecklare som har erfarenhet av Java och Spring för Junior Javautvecklare", "Kort");
        stubSearch("java utvecklare", shorter);
        stubSearch("backend developer java");

        awaitStatus(startRun(true), "DONE");

        assertThat(jdbc.sql("SELECT description_text FROM job WHERE title = 'Junior Javautvecklare'").query(String.class).single())
                .isEqualTo(before);
    }

    @Test
    void resultsCanBeFilteredSortedAndLimited() throws Exception {
        long run = startRun(true);
        awaitStatus(run, "DONE");
        long saab = jdbc.sql("SELECT id FROM job WHERE employer = 'Saab AB'").query(Long.class).single();
        call(patch("/api/jobs/" + saab), "{\"status\":\"APPLIED\"}", 204);

        assertThat(titles(call(get("/api/runs/" + run + "/jobs?status=APPLIED"), null, 200))).containsExactly("Software Developer");
        assertThat(titles(call(get("/api/runs/" + run + "/jobs?q=frontend"), null, 200))).containsExactly("Junior Frontendutvecklare");
        assertThat(titles(call(get("/api/runs/" + run + "/jobs?q=solna"), null, 200))).containsExactly("Junior Frontendutvecklare");
        assertThat(titles(call(get("/api/runs/" + run + "/jobs?minScore=80"), null, 200))).containsExactly("Junior Javautvecklare");
        assertThat(titles(call(get("/api/runs/" + run + "/jobs?top=2"), null, 200))).hasSize(2);
        assertThat(call(get("/api/runs/" + run + "/jobs?sort=deadline"), null, 200)).hasSize(3);
        call(get("/api/runs/" + run + "/jobs?sort=nonsense"), null, 400);
        call(get("/api/runs/999/jobs"), null, 404);
    }

    @Test
    void jobDetailCarriesTheAdTextAndScoreForTheRun() throws Exception {
        long run = startRun(true);
        awaitStatus(run, "DONE");
        long id = jdbc.sql("SELECT id FROM job WHERE title = 'Junior Javautvecklare'").query(Long.class).single();

        JsonNode detail = call(get("/api/jobs/" + id + "?run=" + run), null, 200);

        assertThat(detail.get("description").asText()).contains("erfarenhet av Java");
        assertThat(detail.get("row").get("score").get("fit").asText()).isEqualTo("Strong Java match");
        assertThat(call(get("/api/jobs/" + id), null, 200).get("row").get("score").isNull()).isTrue();
        call(get("/api/jobs/99999"), null, 404);
        call(patch("/api/jobs/99999"), "{\"notes\":\"x\"}", 404);
    }

    @Test
    void exportsDownloadCsvAndXlsx() throws Exception {
        long run = startRun(true);
        awaitStatus(run, "DONE");

        var csv = mvc.perform(get("/api/runs/" + run + "/jobs.csv")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(csv.getContentAsString(StandardCharsets.UTF_8)).contains("Junior Javautvecklare").contains("Rank,Score");
        assertThat(csv.getHeader("Content-Disposition")).contains("jobs-run-" + run + ".csv");

        byte[] xlsx = mvc.perform(get("/api/runs/" + run + "/jobs.xlsx")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsByteArray();
        assertThat(new String(xlsx, 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");
    }

    @Test
    void scoringPromptContainsFactIdsButNeverOwnerNotes() throws Exception {
        JsonNode entry = call(post("/api/cv/entries"),
                "{\"kind\":\"WORK\",\"title\":\"Developer\",\"note\":\"SECRET TODO add dates\",\"organisation\":\"Braive\"}", 201);
        call(post("/api/cv/entries/" + entry.get("id").asLong() + "/facts"), "{\"textEn\":\"Built microservices\"}", 201);

        awaitStatus(startRun(true), "DONE");

        String prompt = claude.calls().stream().filter(c -> c.name().equals("scoreJob")).findFirst().orElseThrow().prompt();
        assertThat(prompt).contains("Built microservices").contains("\"entries\"").doesNotContain("SECRET TODO");
    }

    @Test
    void webDiscoveryAddsUnverifiedAdsThatSortLast() throws Exception {
        claude.respond("discoverJobsWeb", """
                {"jobs":[
                  {"url":"https://career.example.com/1","title":"Java Developer","employer":"Careers AB","publishedDate":"%s",
                   "dateVerifiedOnAdPage":true,"deadline":null,"location":"Stockholm","language":"sv","description":"Java och Spring."},
                  {"url":"https://board.example.com/2","title":"Backend Dev","employer":"Board AB","publishedDate":null,
                   "dateVerifiedOnAdPage":false,"deadline":null,"location":"Stockholm","language":"sv","description":"Backend."}]}"""
                .formatted(java.time.LocalDate.now().minusDays(1)));
        long run = call(post("/api/runs"), "{\"prompt\":\"java\",\"autoContinue\":true,\"includeWeb\":true}", 201).get("id").asLong();

        JsonNode view = awaitStatus(run, "DONE", "FAILED");

        assertThat(view.get("run").get("status").asText()).as(view.get("run").get("message").asText()).isEqualTo("DONE");
        JsonNode rows = call(get("/api/runs/" + run + "/jobs"), null, 200);
        assertThat(rows).hasSize(5);
        JsonNode verified = null;
        JsonNode unverified = null;
        for (JsonNode r : rows) {
            if (r.get("title").asText().equals("Java Developer")) verified = r;
            if (r.get("title").asText().equals("Backend Dev")) unverified = r;
        }
        assertThat(verified.get("source").asText()).isEqualTo("WEB");
        assertThat(verified.get("dateUnverified").asBoolean()).isFalse();
        assertThat(unverified.get("dateUnverified").asBoolean()).isTrue();
        assertThat(unverified.get("evidenceUrl").asText()).isEqualTo("https://board.example.com/2");
        assertThat(claude.calls().stream().anyMatch(c -> c.name().equals("discoverJobsWeb") && c.webTools())).isTrue();
    }

    @Test
    void webDiscoveryFailureIsReportedButDoesNotFailTheRun() throws Exception {
        claude.respond("discoverJobsWeb", task -> {
            throw new ClaudeCallException("web timed out");
        });
        long run = call(post("/api/runs"), "{\"prompt\":\"java\",\"autoContinue\":true,\"includeWeb\":true}", 201).get("id").asLong();

        JsonNode view = awaitStatus(run, "DONE", "FAILED");

        assertThat(view.get("run").get("status").asText()).isEqualTo("DONE");
        assertThat(view.get("run").get("message").asText()).contains("Web discovery failed: web timed out");
    }

    @Test
    void interruptedRunsFailOnStartupButReviewsSurvive() throws Exception {
        jdbc.sql("INSERT INTO pipeline_run (status, step, progress) VALUES ('RUNNING', 'SEARCH', 40), ('QUEUED', 'PROFILE', 0), ('WAITING_FOR_USER', 'PROFILE', 10)").update();

        int failed = runRepository.failInterrupted(Instant.now());

        assertThat(failed).isEqualTo(2);
        assertThat(jdbc.sql("SELECT status FROM pipeline_run ORDER BY id").query(String.class).list())
                .containsExactly("FAILED", "FAILED", "WAITING_FOR_USER");
    }

    @Test
    void startRejectsBlankPromptAndBadOptions() throws Exception {
        call(post("/api/runs"), "{\"prompt\":\"  \"}", 400);
        call(post("/api/runs"), "{\"prompt\":\"java\",\"maxAgeDays\":0}", 400);
        call(post("/api/runs"), "{\"prompt\":\"java\",\"limit\":1000}", 400);
    }
}
