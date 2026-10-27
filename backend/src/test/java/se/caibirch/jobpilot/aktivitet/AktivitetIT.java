package se.caibirch.jobpilot.aktivitet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import se.caibirch.jobpilot.apply.BrowserService;
import se.caibirch.jobpilot.support.PostgresTestConfig;

/**
 * The aktivitetsrapport automation against real PostgreSQL, headless Chromium and fixture pages served from a local HTTP
 * server. The server records everything the page "saved" and every key and click on the Skicka in trap. The real
 * Arbetsförmedlingen is never opened.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class AktivitetIT {

    static final Path FIXTURES = Path.of("src/test/resources/fixtures/aktivitet").toAbsolutePath();
    static final Map<String, String> serving = new ConcurrentHashMap<>();
    static final List<String> saved = Collections.synchronizedList(new ArrayList<>());
    static final List<String> keys = Collections.synchronizedList(new ArrayList<>());
    static final List<String> skickaClicks = Collections.synchronizedList(new ArrayList<>());
    static HttpServer server;
    static Path dataDir;

    static {
        try {
            dataDir = Files.createTempDirectory("jobpilot-aktivitet");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                byte[] body;
                int status = 200;
                if (exchange.getRequestMethod().equals("POST")) {
                    String text = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    switch (path) {
                        case "/saved" -> saved.add(text);
                        case "/key" -> keys.add(text);
                        case "/skicka" -> skickaClicks.add(text);
                        default -> { }
                    }
                    body = new byte[0];
                } else if (serving.containsKey(path)) {
                    body = Files.readAllBytes(FIXTURES.resolve(serving.get(path)));
                    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                } else {
                    status = 404;
                    body = new byte[0];
                }
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    static String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("jobpilot.data-dir", () -> dataDir.toString());
        registry.add("jobpilot.browser.headless", () -> "true");
        registry.add("jobpilot.browser.poll-millis", () -> "200");
        registry.add("jobpilot.browser.suggestion-wait-millis", () -> "4000");
        registry.add("jobpilot.aktivitet.form-url", () -> base() + "/form");
        registry.add("jobpilot.aktivitet.list-url", () -> base() + "/list");
        registry.add("jobpilot.aktivitet.toast-wait-millis", () -> "1500");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    BrowserService browser;

    long searchProfileId;
    long runId;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE job, run_job, pipeline_run, search_profile, job_score, document, aktivitet_log, application_form_snapshot,
                         cv_entry, profile, tag, skill, known_gap, answers_memory RESTART IDENTITY CASCADE""").update();
        serving.clear();
        serving.put("/form", "form.html");
        serving.put("/list", "list_empty.html");
        saved.clear();
        keys.clear();
        skickaClicks.clear();
        searchProfileId = jdbc.sql("INSERT INTO search_profile (prompt, profile_json) VALUES ('java', :j::jsonb) RETURNING id").param("j", """
                {"roleSummary":"Java dev","titles":[],"synonyms":[],"skillsWanted":[],"seniority":"junior","acceptExperienceUpToYears":2,
                 "locations":[],"remoteOk":false,"languages":["sv"],"maxAgeDays":7,"employmentTypes":["heltid"],"exclusions":[],
                 "yrkesrollForAktivitetsrapport":"Systemutvecklare/Programmerare","relevantTags":[],"jobtechQueries":["java"]}""")
                .query(Long.class).single();
        runId = jdbc.sql("INSERT INTO pipeline_run (search_profile_id, status, step, progress) VALUES (:sp, 'DONE', 'DONE', 100) RETURNING id")
                .param("sp", searchProfileId).query(Long.class).single();
    }

    // ---- helpers ----

    long job(String employer, String client, String municipality, String hours, String status, String raw) {
        long id = jdbc.sql("""
                INSERT INTO job (source, source_id, title, employer, client_employer, municipality, employment_type, status, applied_at,
                                 raw_json, dedupe_key, run_id)
                VALUES ('JOBTECH', :e, 'Utvecklare', :e, :c, :m, :h, :s, '2026-10-26T10:00:00Z', :r::jsonb, :e, :run) RETURNING id""")
                .param("e", employer).param("c", client).param("m", municipality).param("h", hours).param("s", status)
                .param("r", raw == null ? "{}" : raw).param("run", runId).query(Long.class).single();
        jdbc.sql("INSERT INTO run_job (run_id, job_id) VALUES (:r, :j)").param("r", runId).param("j", id).update();
        return id;
    }

    long applied(String employer, String municipality) {
        return job(employer, null, municipality, "Heltid", "APPLIED", null);
    }

    JsonNode call(MockHttpServletRequestBuilder req, String body, int status) throws Exception {
        var builder = body == null ? req : req.contentType(MediaType.APPLICATION_JSON).content(body);
        var response = mvc.perform(builder).andReturn().getResponse();
        String out = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as("status of %s: %s", req, out).isEqualTo(status);
        return out.isBlank() ? null : mapper.readTree(out);
    }

    JsonNode plan(long... ids) throws Exception {
        StringBuilder q = new StringBuilder();
        for (long id : ids) {
            q.append(q.length() == 0 ? "" : ",").append(id);
        }
        return call(get("/api/aktivitet/plan?jobIds=" + q), null, 200);
    }

    /** Confirms exactly what the plan proposed. */
    long confirmAll(long... ids) throws Exception {
        ArrayNode entries = mapper.createArrayNode();
        for (JsonNode p : plan(ids)) {
            assertThat(p.get("ineligible").isNull()).as(p.toString()).isTrue();
            entries.add(p.get("entry"));
        }
        return call(post("/api/aktivitet/log"), mapper.createObjectNode().set("entries", entries).toString(), 202).get("runId").asLong();
    }

    JsonNode await(long run, String... statuses) throws Exception {
        List<String> wanted = List.of(statuses);
        long deadline = System.currentTimeMillis() + 60_000;
        JsonNode view = null;
        while (System.currentTimeMillis() < deadline) {
            view = call(get("/api/runs/" + run), null, 200);
            if (wanted.contains(view.get("run").get("status").asText())) {
                return view;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("run " + run + " did not reach " + wanted + ": " + view);
    }

    List<String> results() {
        return jdbc.sql("SELECT result FROM aktivitet_log ORDER BY id").query(String.class).list();
    }

    String status(long jobId) {
        return jdbc.sql("SELECT status FROM job WHERE id = :i").param("i", jobId).query(String.class).single();
    }

    // ---- planning ----

    @Test
    void planProposesAllSevenValuesFromTheAdTheSearchProfileAndTheAppliedDate() throws Exception {
        long id = job("Comstream AB", "Taxi Stockholm", "Stockholm", "Heltid", "APPLIED", null);

        JsonNode entry = plan(id).get(0).get("entry");

        assertThat(entry.get("yrkesroll").asText()).isEqualTo("Systemutvecklare/Programmerare");
        assertThat(entry.get("employerLabel").asText()).isEqualTo("Comstream AB (Taxi Stockholm)");
        assertThat(entry.get("omfattning").asText()).isEqualTo("Heltid");
        assertThat(entry.get("varFinns").asText()).isEqualTo("Sverige");
        assertThat(entry.get("municipality").asText()).isEqualTo("Stockholm");
        assertThat(entry.get("answeredAd").asBoolean()).isTrue();
        assertThat(entry.get("appliedDate").asText()).isEqualTo("2026-10-26");
    }

    @Test
    void onlyAppliedJobsInSwedenWithAMunicipalityAreEligible() throws Exception {
        long newJob = job("A AB", null, "Stockholm", "Heltid", "NEW", null);
        long logged = job("B AB", null, "Stockholm", "Heltid", "LOGGED", null);
        long noPlace = job("C AB", null, null, "Heltid", "APPLIED", null);
        long abroad = job("D AB", null, "Oslo", "Heltid", "APPLIED", "{\"workplace_address\":{\"country\":\"Norge\"}}");
        long fine = applied("E AB", "Solna");

        JsonNode plans = plan(newJob, logged, noPlace, abroad, fine);

        assertThat(plans.get(0).get("ineligible").asText()).contains("Mark the job as applied first");
        assertThat(plans.get(1).get("ineligible").asText()).contains("Already logged");
        assertThat(plans.get(2).get("ineligible").asText()).contains("no municipality");
        assertThat(plans.get(3).get("ineligible").asText()).contains("outside Sweden");
        assertThat(plans.get(4).get("ineligible").isNull()).isTrue();
        call(get("/api/aktivitet/plan?jobIds=99999"), null, 404);
    }

    @Test
    void markingAJobAppliedStampsTheDateAndPartTimeAdsAreMappedToDeltid() throws Exception {
        long id = job("F AB", null, "Nacka", "Deltid", "SHORTLISTED", null);
        jdbc.sql("UPDATE job SET applied_at = NULL WHERE id = :i").param("i", id).update();

        call(patch("/api/jobs/" + id), "{\"status\":\"APPLIED\"}", 204);

        assertThat(jdbc.sql("SELECT applied_at IS NOT NULL FROM job WHERE id = :i").param("i", id).query(Boolean.class).single()).isTrue();
        JsonNode entry = plan(id).get(0).get("entry");
        assertThat(entry.get("omfattning").asText()).isEqualTo("Deltid");
        assertThat(entry.get("appliedDate").asText()).isEqualTo(java.time.LocalDate.now().toString());
    }

    @Test
    void confirmedEntriesAreValidatedBeforeAnythingStarts() throws Exception {
        long id = applied("G AB", "Stockholm");
        ObjectNode good = (ObjectNode) plan(id).get(0).get("entry");
        String ok = mapper.createObjectNode().set("entries", mapper.createArrayNode().add(good)).toString();

        for (String[] bad : new String[][] {{"omfattning", "Halvtid"}, {"varFinns", "Utomlands"}, {"yrkesroll", " "}, {"appliedDate", "2999-01-01"},
                {"municipality", ""}}) {
            ObjectNode copy = good.deepCopy();
            copy.put(bad[0], bad[1]);
            call(post("/api/aktivitet/log"), mapper.createObjectNode().set("entries", mapper.createArrayNode().add(copy)).toString(), 400);
        }
        call(post("/api/aktivitet/log"), "{\"entries\":[]}", 400);
        long notApplied = job("H AB", null, "Stockholm", "Heltid", "NEW", null);
        ObjectNode other = good.deepCopy();
        other.put("jobId", notApplied);
        call(post("/api/aktivitet/log"), mapper.createObjectNode().set("entries", mapper.createArrayNode().add(other)).toString(), 400);
        assertThat(ok).isNotEmpty();
        assertThat(saved).isEmpty();
    }

    // ---- saving ----

    @Test
    void fiveJobsAreSavedInOneGoWithTheFormResetBetweenThem() throws Exception {
        long[] ids = {applied("Comstream AB", "Stockholm"), applied("Saab AB", "Järfälla"), applied("Ravion Nordic AB", "Stockholm"),
                applied("Friday", "Järfälla"), applied("Arbetsförmedlingen", "Solna")};
        jdbc.sql("UPDATE job SET employment_type = 'Deltid' WHERE id = :i").param("i", ids[3]).update();

        long run = confirmAll(ids);
        JsonNode view = await(run, "DONE", "FAILED");

        assertThat(view.get("run").get("status").asText()).as(view.get("run").get("message").asText()).isEqualTo("DONE");
        assertThat(view.get("run").get("kind").asText()).isEqualTo("AKTIVITET");
        assertThat(view.get("run").get("message").asText()).contains("Saved 5 of 5").contains("not submitted");
        assertThat(saved).hasSize(5);
        JsonNode first = mapper.readTree(saved.get(0));
        assertThat(first.get("yrkesroll").asText()).isEqualTo("Systemutvecklare/Programmerare");
        assertThat(first.get("arbetsgivare").asText()).isEqualTo("Comstream AB");
        assertThat(first.get("omfattning").asText()).isEqualTo("heltid");
        assertThat(first.get("var").asText()).isEqualTo("se");
        assertThat(first.get("ort").asText()).isEqualTo("Stockholm");
        assertThat(first.get("annons").asText()).isEqualTo("ja");
        assertThat(first.get("datum").asText()).isEqualTo("2026-10-26");
        assertThat(mapper.readTree(saved.get(1)).get("ort").asText()).isEqualTo("Järfälla");
        assertThat(mapper.readTree(saved.get(3)).get("omfattning").asText()).isEqualTo("deltid");
        assertThat(mapper.readTree(saved.get(4)).get("arbetsgivare").asText()).isEqualTo("Arbetsförmedlingen");
        assertThat(results()).containsOnly("SAVED").hasSize(5);
        for (long id : ids) {
            assertThat(status(id)).isEqualTo("LOGGED");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM aktivitet_log WHERE saved_at IS NOT NULL").query(Long.class).single()).isEqualTo(5);
        // the guard's own rules held on the page
        assertThat(skickaClicks).as("Skicka in was never clicked").isEmpty();
        assertThat(keys).as("only ArrowDown, never Enter").containsOnly("ArrowDown");
        assertThat(browser.openTabCount().orElse(0)).as("the aktivitet tab is closed afterwards").isZero();
    }

    @Test
    void whatTheUserEditedIsWhatIsWritten() throws Exception {
        long id = applied("Comstream AB", "Stockholm");
        ObjectNode entry = (ObjectNode) plan(id).get(0).get("entry");
        entry.put("employerLabel", "Comstream AB (Taxi Stockholm)");
        entry.put("omfattning", "Timmar vid behov");
        entry.put("appliedDate", "2026-10-20");

        long run = call(post("/api/aktivitet/log"), mapper.createObjectNode().set("entries", mapper.createArrayNode().add(entry)).toString(), 202)
                .get("runId").asLong();
        await(run, "DONE", "FAILED");

        JsonNode record = mapper.readTree(saved.get(0));
        assertThat(record.get("arbetsgivare").asText()).isEqualTo("Comstream AB (Taxi Stockholm)");
        assertThat(record.get("omfattning").asText()).isEqualTo("behov");
        assertThat(record.get("datum").asText()).isEqualTo("2026-10-20");
        assertThat(jdbc.sql("SELECT employer_label || '|' || omfattning || '|' || applied_date FROM aktivitet_log").query(String.class).single())
                .isEqualTo("Comstream AB (Taxi Stockholm)|Timmar vid behov|2026-10-20");
    }

    @Test
    void worksWhenTheSuggestionsAreRoleLessListItems() throws Exception {
        serving.put("/form", "form_li.html");
        long id = applied("Comstream AB", "Stockholm");

        JsonNode view = await(confirmAll(id), "DONE", "FAILED");

        assertThat(view.get("run").get("status").asText()).as(view.get("run").get("message").asText()).isEqualTo("DONE");
        assertThat(saved).hasSize(1);
    }

    @Test
    void anAlreadyListedActivityIsASkippedDuplicateAndTheJobStaysApplied() throws Exception {
        serving.put("/list", "list.html"); // lists Comstream AB (Taxi Stockholm) on 2026-10-26
        long dup = job("Comstream AB", "Taxi Stockholm", "Stockholm", "Heltid", "APPLIED", null);
        long fresh = applied("Saab AB", "Järfälla");

        JsonNode view = await(confirmAll(dup, fresh), "DONE", "FAILED");

        assertThat(view.get("run").get("status").asText()).isEqualTo("DONE");
        assertThat(saved).hasSize(1);
        assertThat(mapper.readTree(saved.get(0)).get("arbetsgivare").asText()).isEqualTo("Saab AB");
        assertThat(results()).containsExactly("DUPLICATE", "SAVED");
        assertThat(status(dup)).isEqualTo("APPLIED"); // could be a second application there; the user decides
    }

    @Test
    void aJobThatWasSavedBeforeIsNeverSavedTwice() throws Exception {
        long id = applied("Comstream AB", "Stockholm");
        await(confirmAll(id), "DONE", "FAILED");
        assertThat(saved).hasSize(1);

        JsonNode again = plan(id).get(0);

        assertThat(again.get("ineligible").asText()).contains("Already logged");
        ObjectNode forced = mapper.createObjectNode();
        forced.putArray("entries").add(mapper.createObjectNode().put("jobId", id).put("yrkesroll", "Systemutvecklare/Programmerare")
                .put("employerLabel", "Comstream AB").put("municipality", "Stockholm").put("omfattning", "Heltid").put("varFinns", "Sverige")
                .put("answeredAd", true).put("appliedDate", "2026-10-26"));
        call(post("/api/aktivitet/log"), forced.toString(), 400);
        assertThat(saved).hasSize(1);
    }

    @Test
    void noConfirmationStopsTheWholeBatchAndSparaIsNeverRetried() throws Exception {
        serving.put("/form", "form_no_toast.html");
        long first = applied("Comstream AB", "Stockholm");
        long second = applied("Saab AB", "Järfälla");
        long third = applied("Friday", "Solna");

        JsonNode view = await(confirmAll(first, second, third), "DONE", "FAILED");

        assertThat(view.get("run").get("status").asText()).isEqualTo("FAILED");
        assertThat(view.get("run").get("message").asText()).contains("Stopped after Comstream AB", "check the aktivitetsrapport by hand");
        assertThat(saved).as("Spara was clicked exactly once").hasSize(1);
        assertThat(results()).containsExactly("UNCONFIRMED", "SKIPPED", "SKIPPED");
        assertThat(status(first)).as("not LOGGED without a confirmation").isEqualTo("APPLIED");
        assertThat(status(second)).isEqualTo("APPLIED");
    }

    @Test
    void aPartlyFilledFormNeverReachesSparaButTheNextJobStillRuns() throws Exception {
        long nowhere = applied("Kiruna Gruva AB", "Kiruna"); // the ort list has no Kiruna
        long fine = applied("Saab AB", "Järfälla");

        JsonNode view = await(confirmAll(nowhere, fine), "DONE", "FAILED");

        assertThat(view.get("run").get("status").asText()).isEqualTo("DONE");
        assertThat(saved).hasSize(1);
        assertThat(mapper.readTree(saved.get(0)).get("ort").asText()).isEqualTo("Järfälla");
        assertThat(results()).containsExactly("FAILED", "SAVED");
        assertThat(jdbc.sql("SELECT message FROM aktivitet_log ORDER BY id LIMIT 1").query(String.class).single())
                .startsWith("Not saved: Ort:").contains("no suggestion");
        assertThat(status(nowhere)).isEqualTo("APPLIED");
    }

    @Test
    void waitsAtTheBankIdWallThenContinuesAfterTheUserSignedIn() throws Exception {
        serving.put("/list", "login.html");
        long id = applied("Comstream AB", "Stockholm");

        long run = confirmAll(id);
        JsonNode waiting = await(run, "WAITING_FOR_USER", "DONE", "FAILED");
        assertThat(waiting.get("run").get("message").asText()).contains("Waiting for you to sign in", "BankID");
        assertThat(saved).isEmpty();

        // the user signs in by hand: the same address now shows the report
        serving.put("/list", "list_empty.html");
        browser.withTab(AktivitetService.TAB, p -> p.navigate(base() + "/list"));
        JsonNode done = await(run, "DONE", "FAILED");

        assertThat(done.get("run").get("status").asText()).as(done.get("run").get("message").asText()).isEqualTo("DONE");
        assertThat(saved).hasSize(1);
    }

    @Test
    void aSecondRunCannotStartWhileOneIsWaitingAtTheLoginWall() throws Exception {
        serving.put("/list", "login.html");
        long a = applied("Comstream AB", "Stockholm");
        long b = applied("Saab AB", "Järfälla");
        long run = confirmAll(a);
        await(run, "WAITING_FOR_USER", "DONE", "FAILED");

        ArrayNode entries = mapper.createArrayNode();
        entries.add(plan(b).get(0).get("entry"));
        JsonNode refused = call(post("/api/aktivitet/log"), mapper.createObjectNode().set("entries", entries).toString(), 409);
        assertThat(refused.toString()).containsIgnoringCase("already");

        // let the first run finish so it does not leak into the next test
        serving.put("/list", "list_empty.html");
        browser.withTab(AktivitetService.TAB, p -> p.navigate(base() + "/list"));
        await(run, "DONE", "FAILED");
        assertThat(saved).hasSize(1);
    }

    @Test
    void aRestartFailsWaitingAktivitetAndDocumentRunsButKeepsAWaitingSearchRun() {
        long aktivitet = jdbc.sql("INSERT INTO pipeline_run (kind, status, step, progress) VALUES ('AKTIVITET', 'WAITING_FOR_USER', 'OPEN', 5) RETURNING id")
                .query(Long.class).single();
        long documents = jdbc.sql("INSERT INTO pipeline_run (kind, status, step, progress) VALUES ('DOCUMENTS', 'WAITING_FOR_USER', 'SELECT', 5) RETURNING id")
                .query(Long.class).single();
        long search = jdbc.sql("INSERT INTO pipeline_run (kind, status, step, progress) VALUES ('SEARCH', 'WAITING_FOR_USER', 'PROFILE', 5) RETURNING id")
                .query(Long.class).single();

        new se.caibirch.jobpilot.pipeline.RunRepository(jdbc, mapper).failInterrupted(java.time.Instant.now());

        java.util.function.LongFunction<String> status = id ->
                jdbc.sql("SELECT status FROM pipeline_run WHERE id = :i").param("i", id).query(String.class).single();
        assertThat(status.apply(aktivitet)).isEqualTo("FAILED");
        assertThat(status.apply(documents)).isEqualTo("FAILED");
        assertThat(status.apply(search)).isEqualTo("WAITING_FOR_USER");
    }

    @Test
    void anUnreadableSavedListStopsEverythingBecauseDuplicatesCannotBeRuledOut() throws Exception {
        serving.put("/list", "list_unreadable.html");
        long a = applied("Comstream AB", "Stockholm");
        long b = applied("Saab AB", "Järfälla");

        JsonNode view = await(confirmAll(a, b), "DONE", "FAILED");

        assertThat(view.get("run").get("status").asText()).isEqualTo("FAILED");
        assertThat(view.get("run").get("message").asText()).contains("Could not read the list of saved activities").contains("log these by hand");
        assertThat(saved).isEmpty();
        assertThat(results()).containsExactly("SKIPPED", "SKIPPED");
    }

    @Test
    void aFormThatLooksDifferentIsNotSaved() throws Exception {
        serving.put("/form", "form_different.html");
        long id = applied("Comstream AB", "Stockholm");

        JsonNode view = await(confirmAll(id), "DONE", "FAILED");

        assertThat(view.get("run").get("status").asText()).isEqualTo("FAILED");
        assertThat(view.get("run").get("message").asText()).contains("The form looks different", "Arbetsgivaren", "Save the activities by hand");
        assertThat(saved).isEmpty();
        assertThat(results()).containsExactly("FAILED");
        assertThat(skickaClicks).isEmpty();
    }

    @Test
    void theLogListsEveryAttemptAndTheReminderEndpointAnswers() throws Exception {
        long id = applied("Comstream AB", "Stockholm");
        await(confirmAll(id), "DONE", "FAILED");

        JsonNode rows = call(get("/api/aktivitet/log"), null, 200);
        JsonNode reminder = call(get("/api/aktivitet/reminder"), null, 200);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("result").asText()).isEqualTo("SAVED");
        assertThat(rows.get(0).get("employerLabel").asText()).isEqualTo("Comstream AB");
        assertThat(rows.get(0).get("savedAt").isNull()).isFalse();
        assertThat(reminder.has("active")).isTrue();
        assertThat(reminder.get("forMonth").asText()).matches("\\d{4}-\\d{2}");
    }
}
