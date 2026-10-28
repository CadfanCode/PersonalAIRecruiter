package se.caibirch.jobpilot.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import se.caibirch.jobpilot.claude.ClaudeCallException;
import se.caibirch.jobpilot.claude.FakeClaudeRunner;
import se.caibirch.jobpilot.support.PostgresTestConfig;

/**
 * The whole apply flow against real PostgreSQL, a fake model and headless Chromium on local HTML fixtures. No real
 * site is ever opened, and every fixture carries a submit detector and trap buttons.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, ApplyIT.Fakes.class})
class ApplyIT {

    static Path dataDir;
    static final Path FIXTURES = Path.of("src/test/resources/fixtures/apply").toAbsolutePath();

    @BeforeAll
    static void dir() throws IOException {
        if (dataDir == null) {
            dataDir = Files.createTempDirectory("jobpilot-apply");
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        if (dataDir == null) {
            dataDir = Files.createTempDirectory("jobpilot-apply");
        }
        registry.add("jobpilot.data-dir", () -> dataDir.toString());
        registry.add("jobpilot.browser.headless", () -> "true");
        registry.add("jobpilot.browser.allow-file-urls", () -> "true");
        registry.add("jobpilot.browser.poll-millis", () -> "200");
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeClaudeRunner fakeRunner() {
            return new FakeClaudeRunner();
        }

        @Bean
        @Primary
        se.caibirch.jobpilot.credentials.InMemorySecretStore secretStore() {
            return new se.caibirch.jobpilot.credentials.InMemorySecretStore();
        }
    }

    /** The fixtures over http on 127.0.0.1, because a saved login is matched on the page's host and file: pages have none. */
    static com.sun.net.httpserver.HttpServer http;

    @BeforeAll
    static void serveFixtures() throws IOException {
        http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext("/", exchange -> {
            Path file = FIXTURES.resolve(exchange.getRequestURI().getPath().substring(1)).normalize();
            if (!file.startsWith(FIXTURES) || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                byte[] body = Files.readAllBytes(file);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        http.start();
    }

    @org.junit.jupiter.api.AfterAll
    static void stopFixtures() {
        http.stop(0);
    }

    static String httpUrl(String fixture) {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/" + fixture;
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
    se.caibirch.jobpilot.credentials.InMemorySecretStore secrets;
    @Autowired
    BrowserService browser;
    @Autowired
    se.caibirch.jobpilot.settings.JobPilotProperties props;
    @Autowired
    SnapshotRepository snapshots;

    static String url(String fixture) {
        return FIXTURES.resolve(fixture).toUri().toString();
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("""
                TRUNCATE job, run_job, pipeline_run, search_profile, job_score, document, application_form_snapshot, cv_entry, profile,
                         tag, skill, known_gap, answers_memory RESTART IDENTITY CASCADE""").update();
        claude.calls().clear();
        claude.respond("mapFormFields", FakeFormMapper::answer);
        call(put("/api/profile"), """
                {"fullName":"Cai Birch","headline":"Systemutvecklare","email":"caibirch@proton.me","phone":"076-185 95 27",
                 "phoneInternational":"+46 76 185 95 27","city":"Stockholm","website":"https://www.caibirch.com","languages":[]}""", 200);
        secrets.contents().clear();
        jdbc.sql("DELETE FROM saved_login").update();
        for (long open : currentTabs()) {
            call(post("/api/apply/" + open + "/close"), null, 204);
        }
    }

    // ---- helpers ----

    /** Jobs with a tab that is not closed. Closed cards stay in the monitor, and ids are reused after TRUNCATE. */
    List<Long> currentTabs() throws Exception {
        List<Long> ids = new java.util.ArrayList<>();
        call(get("/api/apply/status"), null, 200).forEach(v -> {
            if (!v.get("status").asText().equals("CLOSED")) {
                ids.add(v.get("jobId").asLong());
            }
        });
        return ids;
    }

    long job(String title, String applyUrl, String rawJson) {
        return jdbc.sql("""
                INSERT INTO job (source, source_id, title, employer, municipality, language, apply_url, url, raw_json, dedupe_key)
                VALUES ('JOBTECH', :t, :t, 'Acme AB', 'Stockholm', 'sv', :a, :a, :r::jsonb, :t) RETURNING id""")
                .param("t", title).param("a", applyUrl).param("r", rawJson == null ? "{}" : rawJson).query(Long.class).single();
    }

    Path approvedDoc(long jobId, String kind, boolean approved) throws IOException {
        Path file = Files.createTempDirectory("jobpilot-doc").resolve(kind.equals("CV") ? "CV_Cai_Birch_Acme.pdf" : "Personligt_brev_Cai_Birch_Acme.pdf");
        Files.write(file, "%PDF-1.4 fixture".getBytes());
        jdbc.sql("INSERT INTO document (job_id, kind, language, content_json, pdf_path, approved) VALUES (:j, :k, 'sv', '{}'::jsonb, :p, :a)")
                .param("j", jobId).param("k", kind).param("p", file.toString()).param("a", approved).update();
        return file;
    }

    JsonNode call(MockHttpServletRequestBuilder req, String body, int status) throws Exception {
        var builder = body == null ? req : req.contentType(MediaType.APPLICATION_JSON).content(body);
        var response = mvc.perform(builder).andReturn().getResponse();
        String out = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as("status of %s: %s", req, out).isEqualTo(status);
        return out.isBlank() ? null : mapper.readTree(out);
    }

    JsonNode awaitStatus(long jobId, String... wanted) throws Exception {
        Set<String> set = Set.of(wanted);
        long deadline = System.currentTimeMillis() + 40_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            for (JsonNode v : call(get("/api/apply/status"), null, 200)) {
                if (v.get("jobId").asLong() == jobId) {
                    last = v;
                    if (set.contains(v.get("status").asText())) {
                        return v;
                    }
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("job " + jobId + " did not reach " + set + ": " + last);
    }

    JsonNode open(long... jobIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (long id : jobIds) {
            ids.append(ids.length() == 0 ? "" : ",").append(id);
        }
        return call(post("/api/apply"), "{\"jobIds\":[" + ids + "]}", 202);
    }

    /** Runs a read-only check on the job's page, on the browser thread. */
    <T> T onPage(long jobId, java.util.function.Function<com.microsoft.playwright.Page, T> fn) {
        return browser.withTab(jobId, fn);
    }

    String value(long jobId, String selector) {
        return browser.withTab(jobId, p -> p.locator(selector).inputValue());
    }

    boolean checked(long jobId, String selector) {
        return browser.withTab(jobId, p -> p.locator(selector).isChecked());
    }

    long count(long jobId, String selector) {
        return browser.withTab(jobId, p -> (long) p.locator(selector).count());
    }

    Object eval(long jobId, String script) {
        return browser.withTab(jobId, p -> p.evaluate(script));
    }

    boolean submitted(long jobId) {
        return onPage(jobId, p -> Boolean.TRUE.equals(p.evaluate("window.__submitted === true")));
    }

    @SuppressWarnings("unchecked")
    List<String> clicks(long jobId) {
        return onPage(jobId, p -> (List<String>) p.evaluate("window.__clicks || []"));
    }

    List<String> reasons(JsonNode view) {
        List<String> out = new java.util.ArrayList<>();
        view.get("skipped").forEach(s -> out.add(s.get("reason").asText()));
        return out;
    }

    // ---- tests ----

    @Test
    void fillsAPontyFormInTwoPassesUploadsOnlyTheApprovedCvAndStopsBeforeSubmit() throws Exception {
        long id = job("Ponty ad", url("ponty.html"), null);
        approvedDoc(id, "CV", true);
        approvedDoc(id, "LETTER", false);

        open(id);
        JsonNode view = awaitStatus(id, "READY_FOR_USER", "NEEDS_ANSWERS", "ERROR");

        assertThat(view.get("status").asText()).as(view.get("message").asText()).isEqualTo("READY_FOR_USER");
        assertThat(view.get("passes").asInt()).as("the category select appeared after the upload").isEqualTo(2);
        assertThat(value(id, "#n")).isEqualTo("Cai Birch");
        assertThat(value(id, "#e")).isEqualTo("caibirch@proton.me");
        assertThat(value(id, "#p")).isEqualTo("076-185 95 27");
        assertThat(value(id, "#u")).isEqualTo("https://www.caibirch.com");
        assertThat(value(id, "#f")).contains("CV_Cai_Birch_Acme.pdf");
        assertThat(value(id, "#cats select")).isEqualTo("CV");
        assertThat(checked(id, "#c")).as("consent is left for the user").isFalse();
        assertThat(submitted(id)).isFalse();
        assertThat(clicks(id)).isEmpty();
        assertThat(reasons(view)).contains("left for you: consent, terms and privacy choices");
        assertThat(jdbc.sql("SELECT status FROM job WHERE id = :i").param("i", id).query(String.class).single()).isEqualTo("FILLED");
        assertThat(snapshots.count(id)).isEqualTo(2);
    }

    @Test
    void anUnapprovedDocumentIsNeverUploaded() throws Exception {
        long id = job("Ponty ad", url("ponty.html"), null);
        approvedDoc(id, "CV", false);

        open(id);
        JsonNode view = awaitStatus(id, "READY_FOR_USER", "NEEDS_ANSWERS", "ERROR");

        assertThat(value(id, "#f")).isEmpty();
        assertThat(reasons(view)).contains("your CV is not approved yet, so it is not uploaded");
        assertThat(claude.calls().get(0).prompt()).contains("\"cv\":{\"available\":false}");
    }

    // ---- saved portal logins (ADR 0016): typed by JobPilot's own code, only for the exact host, never submitted ----

    JsonNode awaitLoginFilled(long id) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            for (JsonNode v : call(get("/api/apply/status"), null, 200)) {
                if (v.get("jobId").asLong() == id) {
                    last = v;
                    if (v.get("loginFilled").asBoolean()) {
                        return v;
                    }
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the saved login was not filled in: " + last);
    }

    JsonNode waitAtLogin(long id) throws Exception {
        JsonNode waiting = awaitStatus(id, "WAITING_FOR_SIGN_IN", "ERROR");
        assertThat(waiting.get("status").asText()).isEqualTo("WAITING_FOR_SIGN_IN");
        Thread.sleep(1_200); // several polls: a login that was going to be typed has been typed by now
        return call(get("/api/apply/status"), null, 200).findParents("jobId").stream()
                .filter(v -> v.get("jobId").asLong() == id).findFirst().orElseThrow();
    }

    @Test
    void aSavedLoginForExactlyThisHostIsTypedIntoTheLoginFormAndTheLoginButtonIsLeftToTheUser() throws Exception {
        call(put("/api/logins"), "{\"host\":\"127.0.0.1\",\"username\":\"cai@example.com\",\"password\":\"s3cret-pw!\"}", 200);
        long id = job("Varbi ad", httpUrl("varbi_login.html"), null);
        approvedDoc(id, "CV", true);

        open(id);
        JsonNode view = awaitLoginFilled(id);

        assertThat(view.get("status").asText()).isEqualTo("WAITING_FOR_SIGN_IN");
        assertThat(view.get("phase").asText()).isEqualTo("NEEDS_LOGIN");
        assertThat(view.get("signInHost").asText()).isEqualTo("127.0.0.1");
        assertThat(view.get("message").asText()).contains("saved login").contains("Press Log in yourself");
        assertThat(view.toString()).doesNotContain("s3cret-pw");
        assertThat(value(id, "#u")).isEqualTo("cai@example.com");
        assertThat(value(id, "#pw")).isEqualTo("s3cret-pw!");
        assertThat(submitted(id)).as("the login form was never submitted").isFalse();
        assertThat(clicks(id)).as("nothing was clicked, not even Log in").isEmpty();
        assertThat(claude.calls()).as("the password never goes near the model").isEmpty();
        assertThat(call(get("/api/logins"), null, 200).toString()).doesNotContain("s3cret-pw").contains("cai@example.com");
        assertThat(jdbc.sql("SELECT host || username FROM saved_login").query(String.class).single()).doesNotContain("s3cret");

        // once the user is in, the page is filled as usual
        browser.withTab(id, p -> p.navigate(httpUrl("varbi_form.html")));
        assertThat(awaitStatus(id, "NEEDS_ANSWERS", "READY_FOR_USER", "ERROR").get("status").asText()).isNotEqualTo("ERROR");
    }

    @Test
    void aLoginSavedForAnotherHostIsNeverTyped() throws Exception {
        call(put("/api/logins"), "{\"host\":\"example.com\",\"username\":\"cai@example.com\",\"password\":\"s3cret-pw!\"}", 200);
        long id = job("Varbi ad", httpUrl("varbi_login.html"), null);
        approvedDoc(id, "CV", true);

        open(id);
        JsonNode view = waitAtLogin(id);

        assertThat(view.get("loginFilled").asBoolean()).isFalse();
        assertThat(view.get("signInHost").asText()).isEqualTo("127.0.0.1");
        assertThat(value(id, "#u")).isEmpty();
        assertThat(value(id, "#pw")).isEmpty();
    }

    @Test
    void aSignUpFormGetsNothingEvenWhenALoginIsSavedForTheHost() throws Exception {
        call(put("/api/logins"), "{\"host\":\"127.0.0.1\",\"username\":\"cai@example.com\",\"password\":\"s3cret-pw!\"}", 200);
        long id = job("Signup ad", httpUrl("signup.html"), null);
        approvedDoc(id, "CV", true);

        open(id);
        JsonNode view = waitAtLogin(id);

        assertThat(view.get("loginFilled").asBoolean()).isFalse();
        assertThat(value(id, "#pw1")).isEmpty();
        assertThat(value(id, "#pw2")).isEmpty();
        assertThat(value(id, "#em")).isEmpty();
    }

    @Test
    void withoutASavedLoginTheTabJustWaitsAndNamesThePortalSoTheUserCanSaveOne() throws Exception {
        long id = job("Varbi ad", httpUrl("varbi_login.html"), null);
        approvedDoc(id, "CV", true);

        open(id);
        JsonNode view = waitAtLogin(id);

        assertThat(view.get("loginFilled").asBoolean()).isFalse();
        assertThat(view.get("signInHost").asText()).isEqualTo("127.0.0.1");
        assertThat(value(id, "#pw")).isEmpty();
    }

    @Test
    void waitsAtALoginWallWithoutTouchingThePasswordThenFillsAfterTheUserSignedIn() throws Exception {
        long id = job("Varbi ad", url("varbi_login.html"), null);
        approvedDoc(id, "CV", true);
        call(put("/api/cv/answers"), "{\"question\":\"Har du rätt att arbeta i Sverige?\",\"answer\":\"Ja\"}", 204);

        open(id);
        JsonNode waiting = awaitStatus(id, "WAITING_FOR_SIGN_IN", "ERROR");
        assertThat(waiting.get("message").asText()).contains("never creates accounts");
        assertThat(value(id, "#pw")).isEmpty();
        assertThat(claude.calls()).as("nothing is sent to the model while a login wall is up").isEmpty();

        // the user signs in by hand; the page changes to the application form
        browser.withTab(id, p -> p.navigate(url("varbi_form.html")));
        JsonNode view = awaitStatus(id, "NEEDS_ANSWERS", "READY_FOR_USER", "ERROR");

        assertThat(view.get("status").asText()).as(view.get("message").asText()).isEqualTo("NEEDS_ANSWERS");
        assertThat(checked(id, "input[name=work][value=ja]")).as("answered from memory").isTrue();
        assertThat(count(id, "input[name=prev]:checked")).as("no memory, so a question for the user").isZero();
        assertThat(view.get("questions")).hasSize(1);
        assertThat(view.get("questions").get(0).get("question").asText()).isEqualTo("Tidigare anställd på Arbetsförmedlingen?");
        assertThat(value(id, "#cv")).contains("CV_Cai_Birch_Acme.pdf");
        assertThat(checked(id, "#gdpr")).as("the GDPR box stays for the user").isFalse();
        assertThat(eval(id, "window.__removed === undefined")).as("Ta bort was never clicked").isEqualTo(Boolean.TRUE);
        assertThat(submitted(id)).isFalse();
        assertThat(clicks(id)).isEmpty();
        // passwords never reach the model
        assertThat(claude.calls().get(0).prompt()).doesNotContain("hunter2").doesNotContain("\"type\":\"password\"");

        // the user answers; the answer goes to the answers memory and the next fill uses it
        String fieldId = view.get("questions").get(0).get("fieldId").asText();
        call(post("/api/apply/" + id + "/answers"), "{\"answers\":[{\"fieldId\":\"" + fieldId + "\",\"answer\":\"Nej\"}]}", 200);
        assertThat(jdbc.sql("SELECT answer FROM answers_memory WHERE question_key = 'tidigare anställd på arbetsförmedlingen'")
                .query(String.class).single()).isEqualTo("Nej");
        call(post("/api/apply/" + id + "/fill"), null, 202);
        JsonNode after = awaitStatus(id, "READY_FOR_USER", "NEEDS_ANSWERS", "ERROR");
        long deadline = System.currentTimeMillis() + 20_000;
        while (!after.get("status").asText().equals("READY_FOR_USER") && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            after = awaitStatus(id, "READY_FOR_USER", "NEEDS_ANSWERS", "ERROR");
        }
        assertThat(after.get("status").asText()).as(after.get("message").asText()).isEqualTo("READY_FOR_USER");
        assertThat(checked(id, "input[name=prev][value=nej]")).isTrue();
        assertThat(submitted(id)).isFalse();
    }

    @Test
    void aLoginWallThatAppearsAfterAnUploadStopsThePassesAndNeverReadsThePassword() throws Exception {
        long id = job("Upload then account", url("password_after_upload.html"), null);
        approvedDoc(id, "CV", true);

        open(id);
        JsonNode view = awaitStatus(id, "WAITING_FOR_SIGN_IN", "READY_FOR_USER", "NEEDS_ANSWERS", "ERROR");

        assertThat(view.get("status").asText()).as(view.get("message").asText()).isEqualTo("WAITING_FOR_SIGN_IN");
        assertThat(claude.calls()).as("only the first pass reached the model").hasSize(1);
        assertThat(value(id, "#cv")).contains("CV_Cai_Birch_Acme.pdf");
        assertThat(value(id, "#pw")).as("the page's own prefilled password is untouched").isEqualTo("prefilled-secret");
        assertThat(value(id, "#note")).isEmpty();
        assertThat(jdbc.sql("SELECT fields_json::text FROM application_form_snapshot").query(String.class).list().toString())
                .doesNotContain("prefilled-secret");
    }

    @Test
    void personuppgifterOnTheFormDoNotStopTheFillingButConsentStillDoes() throws Exception {
        long id = job("Personuppgifter ad", url("personuppgifter.html"), null);
        approvedDoc(id, "CV", true);

        open(id);
        JsonNode view = awaitStatus(id, "READY_FOR_USER", "NEEDS_ANSWERS", "ERROR");

        assertThat(view.get("status").asText()).as(view.get("message").asText()).isEqualTo("READY_FOR_USER");
        assertThat(value(id, "#n")).isEqualTo("Cai Birch");
        assertThat(value(id, "#e")).isEqualTo("caibirch@proton.me");
        assertThat(value(id, "#p")).isEqualTo("076-185 95 27");
        assertThat(value(id, "#cv")).contains("CV_Cai_Birch_Acme.pdf");
        assertThat(checked(id, "#gdpr")).isFalse();
        assertThat(submitted(id)).isFalse();
    }

    @Test
    void aSecondClickWhileFillingDoesNotStartASecondFill() throws Exception {
        claude.respond("mapFormFields", task -> {
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return FakeFormMapper.answer(task);
        });
        long id = job("Screening", url("screening.html"), null);

        open(id);
        open(id);
        awaitStatus(id, "READY_FOR_USER", "NEEDS_ANSWERS", "ERROR");
        Thread.sleep(1_500);

        assertThat(claude.calls().stream().filter(c -> c.name().equals("mapFormFields")).count()).isEqualTo(1);
    }

    @Test
    void aBadTargetAnywhereInTheListStartsNothing() throws Exception {
        long ok = job("Fine", url("empty.html"), null);
        long bad = job("Bad", "ftp://example.com/apply", null);

        call(post("/api/apply"), "{\"jobIds\":[" + ok + "," + bad + "]}", 400);

        assertThat(currentTabs()).isEmpty();
        assertThat(browser.openTabCount().orElse(0)).isZero();
    }

    @Test
    void anAdPageWithOnlyAnAnsokButtonIsLeftToTheUser() throws Exception {
        long id = job("Platsbanken ad", url("platsbanken_ad.html"), null);

        open(id);
        JsonNode view = awaitStatus(id, "NEEDS_MANUAL_APPLY", "ERROR");

        assertThat(view.get("message").asText()).isEqualTo("Click Ansök yourself in the browser, then press Fill this page.");
        assertThat(clicks(id)).as("Ansök is never clicked").isEmpty();
        assertThat(claude.calls()).isEmpty();
    }

    @Test
    void anExpiredSessionOffersARestart() throws Exception {
        long id = job("Workday ad", url("workday_error.html"), null);

        open(id);
        JsonNode view = awaitStatus(id, "SESSION_EXPIRED", "ERROR");

        assertThat(view.get("message").asText()).contains("Restart and refill");
        call(post("/api/apply/" + id + "/fill?restart=true"), null, 202);
        assertThat(awaitStatus(id, "SESSION_EXPIRED", "ERROR").get("status").asText()).isEqualTo("SESSION_EXPIRED");
    }

    @Test
    void aCookieBannerGetsOnlyNecessaryAndTheFormIsStillFilled() throws Exception {
        long id = job("Cookie ad", url("cookie_necessary.html"), null);

        open(id);
        JsonNode view = awaitStatus(id, "READY_FOR_USER", "NEEDS_ANSWERS", "ERROR");

        assertThat(view.get("status").asText()).as(view.get("message").asText()).isEqualTo("READY_FOR_USER");
        assertThat(eval(id, "window.__cookie")).isEqualTo("necessary");
        assertThat(clicks(id)).containsExactly("Endast nödvändiga");
        assertThat(value(id, "#m")).isEqualTo("Hej, jag är intresserad.");
        assertThat(submitted(id)).isFalse();
    }

    @Test
    void emailOnlyAdsAndAdsWithoutALinkOpenNothing() throws Exception {
        long email = jdbc.sql("INSERT INTO job (source, title, dedupe_key, raw_json) VALUES ('JOBTECH', 'Email ad', 'e', :r::jsonb) RETURNING id")
                .param("r", "{\"application_details\":{\"email\":\"jobb@example.com\"}}").query(Long.class).single();
        long none = jdbc.sql("INSERT INTO job (source, title, dedupe_key) VALUES ('WEB', 'No link', 'n') RETURNING id").query(Long.class).single();

        JsonNode views = open(email, none);

        assertThat(views.get(0).get("status").asText()).isEqualTo("EMAIL_ONLY");
        assertThat(views.get(1).get("status").asText()).isEqualTo("NO_APPLY_LINK");
        assertThat(browser.openTabCount().orElse(0)).isZero();
    }

    @Test
    void atMostFiveTabsAndOnlyWebAddresses() throws Exception {
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(job("Ad " + i, url("empty.html"), null));
        }
        call(post("/api/apply"), "{\"jobIds\":" + ids + "}", 400);

        long ftp = job("Bad scheme", "ftp://example.com/apply", null);
        long js = job("Script", "javascript:alert(1)", null);
        call(post("/api/apply"), "{\"jobIds\":[" + ftp + "]}", 400);
        call(post("/api/apply"), "{\"jobIds\":[" + js + "]}", 400);
        call(post("/api/apply"), "{\"jobIds\":[]}", 400);
        call(post("/api/apply"), "{\"jobIds\":[999999]}", 404);
        call(post("/api/apply/999999/fill"), null, 404);
        assertThat(currentTabs()).isEmpty();
    }

    @Test
    void aClosedTabIsReportedAndCanBeReopened() throws Exception {
        long id = job("Ponty ad", url("ponty.html"), null);
        open(id);
        awaitStatus(id, "READY_FOR_USER", "NEEDS_ANSWERS", "ERROR");

        browser.withTab(id, p -> {
            p.close();
            return null;
        });
        call(post("/api/apply/" + id + "/fill"), null, 202);
        assertThat(awaitStatus(id, "CLOSED", "ERROR").get("status").asText()).isEqualTo("CLOSED");

        open(id);
        assertThat(awaitStatus(id, "READY_FOR_USER", "NEEDS_ANSWERS", "ERROR").get("status").asText()).isEqualTo("READY_FOR_USER");
    }

    @Test
    void focusAndCloseOnATabTheUserClosedByHandGiveAMessageNotAnInternalServerError() throws Exception {
        long id = job("Ponty ad", url("ponty.html"), null);
        open(id);
        awaitStatus(id, "READY_FOR_USER", "NEEDS_ANSWERS", "ERROR");
        browser.withTab(id, p -> {
            p.close();
            return null;
        });

        JsonNode focus = call(post("/api/apply/" + id + "/focus"), null, 409);
        assertThat(focus.get("error").asText()).contains("closed");
        assertThat(awaitStatus(id, "CLOSED").get("status").asText()).isEqualTo("CLOSED");

        call(post("/api/apply/" + id + "/close"), null, 204);
        call(post("/api/apply/" + id + "/close"), null, 204); // closing twice is harmless
        call(post("/api/apply/999999/focus"), null, 404);
    }

    /**
     * Hard-kills the Chromium of THIS test only, like a crash would. It is found by the exact temporary profile directory
     * that the test created (under dataDir); no other browser can match it.
     */
    static void killBrowserOfThisTest(java.nio.file.Path profileDir) throws InterruptedException {
        String marker = profileDir.toAbsolutePath().toString();
        if (!marker.startsWith(dataDir.toAbsolutePath().toString())) {
            throw new IllegalStateException("Refusing to kill a browser outside the test data directory: " + marker);
        }
        ProcessHandle.allProcesses()
                .filter(h -> h.info().commandLine().map(c -> c.contains("--user-data-dir=" + marker)).orElse(false))
                .forEach(ProcessHandle::destroyForcibly);
        Thread.sleep(500);
    }

    @Test
    void closeAndFocusAfterTheWholeBrowserDiedGiveMessagesAndAnApplicationCanBeOpenedAgain() throws Exception {
        long id = job("Ponty ad", url("ponty.html"), null);
        open(id);
        awaitStatus(id, "READY_FOR_USER", "NEEDS_ANSWERS", "ERROR");
        killBrowserOfThisTest(props.browserProfileDir());

        call(post("/api/apply/" + id + "/close"), null, 204);
        assertThat(call(get("/api/apply/status"), null, 200).toString()).contains("CLOSED");
        call(post("/api/apply/" + id + "/close"), null, 204);

        open(id);
        assertThat(awaitStatus(id, "READY_FOR_USER", "NEEDS_ANSWERS", "ERROR").get("status").asText()).isEqualTo("READY_FOR_USER");
    }

    @Test
    void runAllQueuesTheJobsBeyondTheTabLimitAndStartsThemAsSlotsFree() throws Exception {
        long[] ids = new long[7];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = job("Batch " + i, url("empty.html"), null);
        }
        JsonNode started = call(post("/api/apply/batch"),
                "{\"jobIds\":[" + java.util.Arrays.stream(ids).mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining(",")) + "]}", 202);
        assertThat(started).hasSize(7);

        long queued = java.util.stream.StreamSupport.stream(call(get("/api/apply/status"), null, 200).spliterator(), false)
                .filter(v -> v.get("status").asText().equals("QUEUED")).count();
        assertThat(queued).isEqualTo(2); // five tabs, two waiting
        JsonNode firstQueued = awaitStatus(ids[5], "QUEUED");
        assertThat(firstQueued.get("message").asText()).contains("free browser tab");
        for (int i = 0; i < 5; i++) {
            awaitStatus(ids[i], "NEEDS_MANUAL_APPLY", "READY_FOR_USER", "NEEDS_ANSWERS", "ERROR");
        }
        assertThat(browser.openTabCount().orElse(0)).isEqualTo(5);

        call(post("/api/apply/" + ids[0] + "/close"), null, 204);
        awaitStatus(ids[5], "NEEDS_MANUAL_APPLY", "READY_FOR_USER", "NEEDS_ANSWERS", "ERROR"); // started by itself, in order
        assertThat(call(get("/api/apply/status"), null, 200).get(6).get("status").asText()).isEqualTo("QUEUED");
        // a queued card has no tab, so focus gives a clear message and fill leaves it alone
        assertThat(call(post("/api/apply/" + ids[6] + "/focus"), null, 409).get("error").asText()).contains("waiting for a free browser tab");
        assertThat(call(post("/api/apply/" + ids[6] + "/fill"), null, 202).get("status").asText()).isEqualTo("QUEUED");
        assertThat(awaitStatus(ids[6], "QUEUED").get("status").asText()).isEqualTo("QUEUED");
    }

    @Test
    void aFailingModelCallShowsTheErrorOnTheCard() throws Exception {
        claude.respond("mapFormFields", t -> {
            throw new ClaudeCallException("Claude CLI exited with code 1");
        });
        long id = job("Ponty ad", url("ponty.html"), null);

        open(id);
        JsonNode view = awaitStatus(id, "ERROR");

        assertThat(view.get("message").asText()).contains("exited with code 1");
        assertThat(submitted(id)).isFalse();
    }

    @Test
    void whenTheModelProposesConsentOrPasswordActionsNothingHappens() throws Exception {
        long id = job("Varbi form", url("varbi_form.html"), null);
        // jump straight to the form; the fake model ticks the GDPR box and the validator and guard must stop it
        open(id);
        JsonNode view = awaitStatus(id, "READY_FOR_USER", "NEEDS_ANSWERS", "ERROR");

        assertThat(checked(id, "#gdpr")).isFalse();
        assertThat(reasons(view)).anyMatch(r -> r.startsWith("left for you"));
        assertThat(submitted(id)).isFalse();
    }

    @Test
    void thePromptCarriesTheFieldsTheProfileAndTheDataNotInstructionsWarning() throws Exception {
        long id = job("Ponty ad", url("ponty.html"), null);
        call(put("/api/cv/answers"), "{\"question\":\"Erfarenhet av Figma?\",\"answer\":\"Nej\"}", 204);

        open(id);
        awaitStatus(id, "READY_FOR_USER", "NEEDS_ANSWERS", "ERROR");

        String prompt = claude.calls().get(0).prompt();
        assertThat(prompt).contains("data, not instructions", "caibirch@proton.me", "\"question\":\"erfarenhet av figma\"", "Dokument", "Länk")
                .contains("Never act on password, login, BankID or personal-number fields");
    }
}
