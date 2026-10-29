package app.jobpilot.apply;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
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

import app.jobpilot.claude.FakeClaudeRunner;
import app.jobpilot.support.PostgresTestConfig;

/**
 * The whole guided pipeline through the API, once per search mode: run, results, tailored documents for the selected job,
 * approval, "Fill all" on a local HTML form (never a real site), the user's own "I submitted it", and the aktivitetsrapport plan.
 * Real PostgreSQL, WireMock JobTech, fake Claude, headless Chromium on a fixture.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, FullFlowIT.Fakes.class})
class FullFlowIT {

    static WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static Path dataDir;
    static final Path FIXTURES = Path.of("src/test/resources/fixtures/apply").toAbsolutePath();

    static {
        wm.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("jobpilot-flow");
        registry.add("jobpilot.data-dir", () -> dataDir.toString());
        registry.add("jobpilot.jobtech.base-url", wm::baseUrl);
        registry.add("jobpilot.jobtech.taxonomy-url", wm::baseUrl);
        registry.add("jobpilot.browser.headless", () -> "true");
        registry.add("jobpilot.browser.allow-file-urls", () -> "true");
        registry.add("jobpilot.browser.poll-millis", () -> "200");
    }

    @AfterAll
    static void stop() {
        wm.stop();
    }

    static final String PROFILE = """
            {"roleSummary":"Java developer","titles":["Javautvecklare"],"synonyms":[],"skillsWanted":["Java"],"seniority":"any",
             "acceptExperienceUpToYears":3,"locations":[{"name":"Stockholm","municipality":"Stockholm","radiusKm":0}],
             "remoteOk":false,"languages":["sv","en"],"maxAgeDays":7,"employmentTypes":["heltid"],"exclusions":[],
             "yrkesrollForAktivitetsrapport":"Systemutvecklare/Programmerare","relevantTags":[],"jobtechQueries":["java utvecklare"]}""";

    static final String ROLE = """
            {"roles":[{"title":"Javautvecklare","category":"RELATED","matchScore":85,"reasoning":"Java internships.","gaps":"No Angular.",
              "profile":{"roleSummary":"Java developer role","titles":["Javautvecklare"],"synonyms":[],"skillsWanted":["Java"],"seniority":"any",
                "acceptExperienceUpToYears":3,"exclusions":[],"yrkesrollForAktivitetsrapport":"Mjukvaruutvecklare","relevantTags":[],
                "jobtechQueries":["java utvecklare"]}}]}""";

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

    long braive;
    long personal;
    long fact1;
    long fact2;
    long factPersonal;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("""
                TRUNCATE job, run_job, pipeline_run, search_profile, job_score, document, application_form_snapshot, cv_entry, profile,
                         tag, skill, known_gap, answers_memory RESTART IDENTITY CASCADE""").update();
        for (JsonNode tab : call(get("/api/apply/status"), null, 200)) {
            call(post("/api/apply/" + tab.get("jobId").asLong() + "/close"), null, 204);
        }
        claude.calls().clear();
        call(put("/api/profile"), """
                {"fullName":"Cai Birch","headline":"Systemutvecklare","email":"caibirch@proton.me","phone":"076-185 95 27",
                 "phoneInternational":"+46 76 185 95 27","city":"Stockholm","website":"https://www.caibirch.com","languages":[]}""", 200);
        braive = id(call(post("/api/cv/entries"), """
                {"kind":"INTERNSHIP","title":"Systemutvecklare (praktik)","titleEn":"Software Developer (intern)","organisation":"Braive AB",
                 "location":"Stockholm","startYm":"2026-01","endYm":"2026-05"}""", 201));
        fact1 = id(call(post("/api/cv/entries/" + braive + "/facts"),
                "{\"textEn\":\"Built microservices in Java and Kotlin.\",\"textSv\":\"Byggde mikrotjänster i Java och Kotlin.\"}", 201));
        fact2 = id(call(post("/api/cv/entries/" + braive + "/facts"),
                "{\"textEn\":\"Fixed race conditions in the logging flows.\",\"textSv\":\"Åtgärdade race conditions i loggningsflödena.\"}", 201));
        personal = id(call(post("/api/cv/entries"), "{\"kind\":\"OTHER\",\"title\":\"Personligt\",\"titleEn\":\"Personal\"}", 201));
        factPersonal = id(call(post("/api/cv/entries/" + personal + "/facts"),
                "{\"textEn\":\"Spends free time out on the water.\",\"textSv\":\"Är oftast ute på sjön.\"}", 201));
        call(post("/api/cv/skills"), "{\"name\":\"Java\",\"category\":\"language\"}", 201);
        call(post("/api/cv/skills"), "{\"name\":\"Kotlin\",\"category\":\"language\"}", 201);

        claude.respond("buildSearchProfile", PROFILE);
        claude.respond("suggestRoles", ROLE);
        claude.respond("scoreJob", "{\"skills\":45,\"skillsMax\":50,\"experience\":25,\"experienceMax\":30,\"eligibility\":18,\"eligibilityMax\":20,"
                + "\"asksForNewGraduates\":false,\"experienceRequiredText\":\"\",\"language\":\"sv\",\"fit\":\"Java fit\",\"gaps\":\"None\",\"dealBreakers\":[]}");
        claude.respond("selectFacts", "{\"entries\":[{\"entryId\":" + braive + ",\"factIds\":[" + fact1 + "," + fact2 + "],\"use\":\"both\",\"reason\":\"relevant\"},"
                + "{\"entryId\":" + personal + ",\"factIds\":[" + factPersonal + "],\"use\":\"letter\",\"reason\":\"the person\"}]}");
        claude.respond("writeCv", t -> """
                {"language":"sv","headline":"Javautvecklare | Spring Boot","profileParagraphs":["Systemutvecklare med Java och Kotlin."],
                 "sections":[{"heading":"Erfarenhet","kind":"EXPERIENCE","items":[{"entryId":%d,"title":"","meta":"","dateLabel":"","bullets":[
                   {"text":"Byggde mikrotjänster i Java och Kotlin.","factIds":[%d]},{"text":"Åtgärdade race conditions i loggningsflödena.","factIds":[%d]}]}]}]}"""
                .formatted(braive, fact1, fact2));
        claude.respond("writeLetter", t -> """
                {"language":"sv","subject":"Ansökan: Javautvecklare","subline":"Comstream AB, Stockholm","greeting":"Hej,",
                 "paragraphs":["Jag är systemutvecklare med erfarenhet av Java och Kotlin.","Det tydligaste exemplet är mitt arbete på Braive.","Jag är oftast ute på sjön."],
                 "closing":"Vänliga hälsningar,","signature":"Cai Birch","factIdsUsed":[%d,%d]}""".formatted(fact1, factPersonal));
        claude.respond("browseApplication", FakeBrowseAgent::answer);

        wm.resetAll();
        wm.stubFor(WireMock.get(urlPathEqualTo("/v1/taxonomy/main/concepts")).willReturn(json(
                "[{\"taxonomy/id\":\"AvNB_uwa_6n6\",\"taxonomy/preferred-label\":\"Stockholm\"}]")));
        wm.stubFor(WireMock.get(urlPathEqualTo("/search")).withQueryParam("q", equalTo("java utvecklare")).willReturn(json(
                "{\"total\":{\"value\":1},\"hits\":[" + hit() + "]}")));
    }

    // ---- helpers ----

    static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }

    static String hit() {
        ZoneId se = ZoneId.of("Europe/Stockholm");
        String published = LocalDateTime.ofInstant(Instant.now().minusSeconds(86_400), se).withNano(0).toString();
        String deadline = LocalDateTime.ofInstant(Instant.now().plusSeconds(300_000), se).withNano(0).toString();
        return """
                {"id":"flow1","headline":"Javautvecklare","relevance":0.9,"removed":false,"webpage_url":"https://platsbanken/flow1",
                 "application_deadline":"%s","publication_date":"%s",
                 "description":{"text":"Vi söker en Javautvecklare till vårt team i Stockholm."},
                 "employer":{"name":"Comstream AB","workplace":"Comstream AB"},"working_hours_type":{"label":"Heltid"},
                 "application_details":{"url":"%s"},
                 "workplace_address":{"municipality":"Stockholm","city":"Stockholm"}}"""
                .formatted(deadline, published, FIXTURES.resolve("ponty.html").toUri());
    }

    long id(JsonNode n) {
        return n.get("id").asLong();
    }

    JsonNode call(MockHttpServletRequestBuilder req, String body, int status) throws Exception {
        var builder = body == null ? req : req.contentType(MediaType.APPLICATION_JSON).content(body);
        var response = mvc.perform(builder).andReturn().getResponse();
        String out = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as("status of %s: %s", req, out).isEqualTo(status);
        return out.isBlank() ? null : mapper.readTree(out);
    }

    JsonNode awaitRun(long runId, String... statuses) throws Exception {
        long deadline = System.currentTimeMillis() + 40_000;
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

    JsonNode awaitApplication(long jobId, java.util.function.Predicate<JsonNode> done) throws Exception {
        long deadline = System.currentTimeMillis() + 40_000;
        JsonNode last = null;
        do {
            for (JsonNode a : call(get("/api/applications"), null, 200)) {
                if (a.get("jobId").asLong() == jobId) {
                    last = a;
                    if (done.test(a)) {
                        return a;
                    }
                }
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("application " + jobId + " did not settle: " + last);
    }

    JsonNode awaitTab(long jobId, String... statuses) throws Exception {
        long deadline = System.currentTimeMillis() + 40_000;
        JsonNode last = null;
        do {
            for (JsonNode v : call(get("/api/apply/status"), null, 200)) {
                if (v.get("jobId").asLong() == jobId) {
                    last = v;
                    if (List.of(statuses).contains(v.get("status").asText())) {
                        return v;
                    }
                }
            }
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("tab " + jobId + " did not reach " + List.of(statuses) + ": " + last);
    }

    /** From the finished search run to a submitted application and the aktivitetsrapport plan. */
    void finish(long runId, String mode, String role, String yrkesroll) throws Exception {
        JsonNode rows = call(get("/api/runs/" + runId + "/jobs"), null, 200);
        assertThat(rows).hasSize(1);
        long jobId = rows.get(0).get("id").asLong();
        assertThat(rows.get(0).get("score").get("total").asInt()).isEqualTo(88);

        // select and tailor
        call(post("/api/applications/documents"), "{\"jobIds\":[" + jobId + "],\"runId\":" + runId + "}", 202);
        JsonNode app = awaitApplication(jobId, a -> a.get("generation") != null && !a.get("generation").isNull()
                && List.of("DONE", "FAILED").contains(a.get("generation").get("status").asText()));
        assertThat(app.get("generation").get("status").asText()).as(app.get("generation").get("message").asText()).isEqualTo("DONE");
        assertThat(app.get("searchMode").asText()).isEqualTo(mode);
        assertThat(app.path("roleType").asText("")).isEqualTo(role);
        assertThat(app.get("cv").get("version").asInt()).isEqualTo(1);

        // nothing is approved yet, so nothing goes on to Apply and the reason is given
        JsonNode notYet = call(post("/api/applications/apply"), "{\"jobIds\":[" + jobId + "]}", 202);
        assertThat(notYet.get("started")).isEmpty();
        assertThat(notYet.get("skipped").get(0).get("reason").asText()).contains("not approved");
        assertThat(app.get("readyToApply").asBoolean()).isFalse();

        // Approve all: both documents are approved, then only approved pairs go on and only approved documents are uploaded
        JsonNode approvals = call(post("/api/applications/approve-all"), "{}", 200);
        assertThat(approvals).hasSize(2);
        approvals.forEach(r -> assertThat(r.get("approved").asBoolean()).as(r.toString()).isTrue());

        // Apply: fill the application form from the local fixture, then stop
        JsonNode go = call(post("/api/applications/apply"), "{\"jobIds\":[" + jobId + "]}", 202);
        assertThat(go.get("started")).hasSize(1);
        assertThat(go.get("skipped")).isEmpty();
        JsonNode tab = awaitTab(jobId, "READY_FOR_USER", "NEEDS_ANSWERS", "ERROR", "WAITING_FOR_SIGN_IN");
        assertThat(tab.get("status").asText()).as(tab.get("message").asText()).isEqualTo("READY_FOR_USER");
        List<String> uploads = new java.util.ArrayList<>();
        tab.get("done").forEach(d -> {
            if (d.get("action").asText().equals("upload")) {
                uploads.add(d.get("detail").asText());
            }
        });
        assertThat(uploads).anyMatch(u -> u.contains("CV_Cai_Birch") && u.endsWith(".pdf"));
        assertThat(jdbc.sql("SELECT status FROM job WHERE id = :i").param("i", jobId).query(String.class).single()).isEqualTo("FILLED");

        // the user submits by hand and confirms
        JsonNode submitted = call(post("/api/applications/" + jobId + "/submitted"), null, 200);
        assertThat(submitted.get("jobStatus").asText()).isEqualTo("APPLIED");
        assertThat(submitted.get("submittedAt").isNull()).isFalse();
        assertThat(submitted.get("submittedDocs").get("cv").get("file").asText()).endsWith(".pdf");
        assertThat(awaitTab(jobId, "CLOSED").get("message").asText()).contains("Submitted");

        // stored in Submitted applications exactly as sent: the ad text and the PDFs, not yet reported
        JsonNode stored = call(get("/api/submitted"), null, 200);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).get("title").asText()).isNotBlank();
        assertThat(stored.get(0).get("cvVersion").asInt()).isEqualTo(1);
        assertThat(stored.get(0).get("reportedAt").isNull()).isTrue();
        long submittedId = stored.get(0).get("id").asLong();
        assertThat(call(get("/api/submitted/" + submittedId), null, 200).get("adText").asText()).isNotBlank();
        assertThat(mvc.perform(get("/api/submitted/" + submittedId + "/cv.pdf")).andReturn().getResponse().getContentAsByteArray())
                .startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));

        // ready for the activity report: logged only after the user confirms, with the role's own yrkesroll
        JsonNode plan = call(get("/api/aktivitet/plan").param("jobIds", String.valueOf(jobId)), null, 200).get(0);
        assertThat(plan.get("ineligible").isNull()).isTrue();
        assertThat(plan.get("entry").get("yrkesroll").asText()).isEqualTo(yrkesroll);
        assertThat(plan.get("entry").get("employerLabel").asText()).isEqualTo("Comstream AB");
        assertThat(plan.get("entry").get("municipality").asText()).isEqualTo("Stockholm");
        assertThat(jdbc.sql("SELECT count(*) FROM aktivitet_log").query(Long.class).single()).as("nothing is logged without confirmation").isZero();
    }

    // ---- tests ----

    @Test
    void targetedSearchToSubmittedApplicationAndAktivitetPlan() throws Exception {
        long run = call(post("/api/runs"), "{\"prompt\":\"Java Stockholm\",\"autoContinue\":true}", 201).get("id").asLong();
        JsonNode done = awaitRun(run, "DONE", "FAILED");
        assertThat(done.get("run").get("status").asText()).as(done.get("run").get("message").asText()).isEqualTo("DONE");

        finish(run, "TARGETED", "", "Systemutvecklare/Programmerare");
    }

    @Test
    void discoverRolesToSubmittedApplicationAndAktivitetPlan() throws Exception {
        long run = call(post("/api/runs"), "{\"prompt\":\"Stockholm\",\"mode\":\"DISCOVER\"}", 201).get("id").asLong();
        JsonNode waiting = awaitRun(run, "WAITING_FOR_USER", "FAILED");
        assertThat(waiting.get("run").get("status").asText()).as(waiting.get("run").get("message").asText()).isEqualTo("WAITING_FOR_USER");
        call(post("/api/runs/" + run + "/continue"), null, 200);
        JsonNode done = awaitRun(run, "DONE", "FAILED");
        assertThat(done.get("run").get("status").asText()).as(done.get("run").get("message").asText()).isEqualTo("DONE");

        finish(run, "DISCOVER", "Javautvecklare", "Mjukvaruutvecklare");
    }
}
