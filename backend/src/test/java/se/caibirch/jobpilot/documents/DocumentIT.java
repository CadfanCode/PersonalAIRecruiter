package se.caibirch.jobpilot.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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

import se.caibirch.jobpilot.claude.FakeClaudeRunner;
import se.caibirch.jobpilot.support.PostgresTestConfig;

/** Generation, validation, the single rewrite, rendering, editing and approval against real PostgreSQL and a fake Claude. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, DocumentIT.Fakes.class})
class DocumentIT {

    static Path dataDir;

    @BeforeAll
    static void dir() throws IOException {
        dataDir = Files.createTempDirectory("jobpilot-docs");
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        if (dataDir == null) {
            dataDir = Files.createTempDirectory("jobpilot-docs");
        }
        registry.add("jobpilot.data-dir", () -> dataDir.toString());
    }

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
    long teaching;
    long yh;
    long personal;
    long fBraive1;
    long fBraive2;
    long fTeach;
    long fYh;
    long fPersonal;
    long jobId;
    long runId;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("""
                TRUNCATE job, run_job, application, pipeline_run, search_profile, job_score, document, cv_entry, profile, tag, skill,
                         known_gap, answers_memory RESTART IDENTITY CASCADE""").update();
        claude.calls().clear();
        call(put("/api/profile"), """
                {"fullName":"Cai Birch","headline":"Systemutvecklare","email":"cai@example.com","phone":"076-185 95 27",
                 "city":"Stockholm","website":"https://www.caibirch.com","languages":[{"language":"English","level":"native"},
                 {"language":"Swedish","level":"fluent"}]}""", 200);
        braive = id(call(post("/api/cv/entries"), """
                {"kind":"INTERNSHIP","title":"Systemutvecklare (praktik)","titleEn":"Software Developer (intern)","organisation":"Braive AB",
                 "location":"Stockholm","startYm":"2026-01","endYm":"2026-05","note":"SECRET OWNER TODO","tags":["java"]}""", 201));
        fBraive1 = id(call(post("/api/cv/entries/" + braive + "/facts"),
                "{\"textEn\":\"Built microservices in Java and Kotlin.\",\"textSv\":\"Byggde mikrotjänster i Java och Kotlin.\"}", 201));
        fBraive2 = id(call(post("/api/cv/entries/" + braive + "/facts"),
                "{\"textEn\":\"Fixed race conditions in the logging flows.\",\"textSv\":\"Åtgärdade race conditions i loggningsflödena.\"}", 201));
        teaching = id(call(post("/api/cv/entries"), """
                {"kind":"WORK","title":"Laborationslärare","titleEn":"Lab teacher","organisation":"Loughborough University",
                 "location":"Storbritannien","startYm":"2018-01","endYm":"2021-01","tags":["teaching"]}""", 201));
        fTeach = id(call(post("/api/cv/entries/" + teaching + "/facts"),
                "{\"textEn\":\"Taught labs for over 100 students.\",\"textSv\":\"Höll laborationer för över 100 studenter.\"}", 201));
        yh = id(call(post("/api/cv/entries"), """
                {"kind":"EDUCATION","title":"Yrkeshögskoleexamen, mjukvaruutveckling med Java","titleEn":"Higher Vocational Diploma, Java",
                 "organisation":"YH Akademin","location":"Sundsvall","startYm":"2024","endYm":"2026"}""", 201));
        fYh = id(call(post("/api/cv/entries/" + yh + "/facts"),
                "{\"textEn\":\"Two-year programme focused on Java back-end development.\",\"textSv\":\"Tvåårig utbildning med fokus på backend i Java.\"}", 201));
        personal = id(call(post("/api/cv/entries"), "{\"kind\":\"OTHER\",\"title\":\"Personligt\",\"titleEn\":\"Personal\"}", 201));
        fPersonal = id(call(post("/api/cv/entries/" + personal + "/facts"),
                "{\"textEn\":\"Spends free time out on the water.\",\"textSv\":\"Är oftast ute på sjön.\"}", 201));
        call(post("/api/cv/skills"), "{\"name\":\"Java\",\"category\":\"language\"}", 201);
        call(post("/api/cv/skills"), "{\"name\":\"Kotlin\",\"category\":\"language\"}", 201);
        call(post("/api/cv/skills"), "{\"name\":\"Spring Boot\",\"category\":\"backend\"}", 201);
        call(put("/api/cv/gaps"), "{\"gaps\":[\"No Angular\"]}", 200);

        long sp = jdbc.sql("INSERT INTO search_profile (prompt, profile_json) VALUES ('java', :j::jsonb) RETURNING id")
                .param("j", """
                        {"roleSummary":"Java dev","titles":["Javautvecklare"],"synonyms":[],"skillsWanted":["Java"],"seniority":"junior",
                         "acceptExperienceUpToYears":2,"locations":[],"remoteOk":false,"languages":["sv","en"],"maxAgeDays":7,
                         "employmentTypes":["heltid"],"exclusions":[],"yrkesrollForAktivitetsrapport":"Systemutvecklare/Programmerare",
                         "relevantTags":["java"],"jobtechQueries":["java"]}""").query(Long.class).single();
        runId = jdbc.sql("INSERT INTO pipeline_run (search_profile_id, status, step, progress) VALUES (:sp, 'DONE', 'DONE', 100) RETURNING id")
                .param("sp", sp).query(Long.class).single();
        jobId = addJob("Junior Java-utvecklare", "Comstream AB", "sv", "Vi söker en Javautvecklare till vårt team i Stockholm.", runId);

        claude.respond("selectFacts", t -> selection(braive, fBraive1, fBraive2));
        claude.respond("writeCv", t -> cvJson("sv", "Byggde mikrotjänster i Java och Kotlin.", "Åtgärdade race conditions i loggningsflödena."));
        claude.respond("writeLetter", t -> letterJson("sv", "Jag är systemutvecklare med erfarenhet av Java och Kotlin."));
        claude.respond("rewriteForViolations", t -> {
            throw new IllegalStateException("rewrite was not expected in this test");
        });
    }

    // ---- helpers ----

    long addJob(String title, String employer, String language, String description, long run) {
        long id = jdbc.sql("""
                INSERT INTO job (source, source_id, title, employer, municipality, language, description_text, dedupe_key, run_id)
                VALUES ('JOBTECH', :t, :t, :e, 'Stockholm', :l, :d, :k, :r) RETURNING id""")
                .param("t", title).param("e", employer).param("l", language).param("d", description)
                .param("k", employer + "|" + title).param("r", run).query(Long.class).single();
        jdbc.sql("INSERT INTO run_job (run_id, job_id) VALUES (:r, :j)").param("r", run).param("j", id).update();
        return id;
    }

    String selection(long entry, long... facts) {
        StringBuilder f = new StringBuilder();
        for (long x : facts) {
            f.append(f.length() == 0 ? "" : ",").append(x);
        }
        return "{\"entries\":[{\"entryId\":" + entry + ",\"factIds\":[" + f + "],\"use\":\"both\",\"reason\":\"relevant\"},"
                + "{\"entryId\":" + personal + ",\"factIds\":[" + fPersonal + "],\"use\":\"letter\",\"reason\":\"shows the person\"}]}";
    }

    String cvJson(String language, String bullet1, String bullet2) {
        return """
                {"language":"%s","headline":"Javautvecklare | Spring Boot","profileParagraphs":["Systemutvecklare med Java och Kotlin."],
                 "sections":[
                  {"heading":"Tekniska kunskaper","kind":"SKILLS","items":[{"entryId":0,"title":"Java och backend","meta":"Java, Kotlin, Spring Boot","dateLabel":"","bullets":[]}]},
                  {"heading":"Erfarenhet","kind":"EXPERIENCE","items":[{"entryId":%d,"title":"","meta":"","dateLabel":"","bullets":[
                    {"text":"%s","factIds":[%d]},{"text":"%s","factIds":[%d]}]}]}]}"""
                .formatted(language, braive, bullet1, fBraive1, bullet2, fBraive2);
    }

    String letterJson(String language, String opening) {
        boolean sv = language.equals("sv");
        return """
                {"language":"%s","subject":"%s Junior Java-utvecklare","subline":"Comstream AB, Stockholm","greeting":"%s",
                 "paragraphs":["%s","Det tydligaste exemplet är mitt arbete på Braive.","Jag är oftast ute på sjön."],
                 "closing":"%s","signature":"Cai Birch","factIdsUsed":[%d,%d]}"""
                .formatted(language, sv ? "Ansökan:" : "Application:", sv ? "Hej," : "Hello,", opening,
                        sv ? "Vänliga hälsningar," : "Kind regards,", fBraive1, fPersonal);
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

    JsonNode generate(long job) throws Exception {
        long run = call(post("/api/jobs/" + job + "/documents"), "{}", 202).get("runId").asLong();
        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode view;
        do {
            view = call(get("/api/runs/" + run), null, 200);
            String status = view.get("run").get("status").asText();
            if (status.equals("DONE") || status.equals("FAILED")) {
                return view;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("documents run did not finish: " + view);
    }

    JsonNode docs(long job) throws Exception {
        return call(get("/api/jobs/" + job + "/documents"), null, 200);
    }

    List<String> codes(JsonNode doc) {
        List<String> out = new ArrayList<>();
        doc.get("report").get("violations").forEach(v -> out.add(v.get("code").asText()));
        return out;
    }

    static String pdfText(byte[] pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(d);
        }
    }

    // ---- tests ----

    // ---- "Ask Claude": fine adjustments through chat ----

    String revision(boolean changed, String reply, String documentJson) {
        return "{\"changed\":" + changed + ",\"reply\":\"" + reply + "\",\"document\":" + documentJson + "}";
    }

    JsonNode chat(long documentId, String message, int status) throws Exception {
        return call(post("/api/documents/" + documentId + "/chat"), "{\"message\":\"" + message + "\",\"history\":[]}", status);
    }

    @Test
    void chatRevisesTheDocumentSavesANewVersionAndKeepsDatabaseOwnedFieldsAndRevokesApproval() throws Exception {
        generate(jobId);
        long cvId = docs(jobId).get("cv").get("id").asLong();
        call(post("/api/documents/" + cvId + "/approve"), null, 200);
        // the model also tampers with the employer and the title of the experience item: the database wins
        String revised = cvJson("sv", "Byggde och förbättrade mikrotjänster i Java och Kotlin.", "Åtgärdade race conditions i loggningsflödena.")
                .replace("\"entryId\":" + braive + ",\"title\":\"\"", "\"entryId\":" + braive + ",\"title\":\"Hacked AB\"");
        claude.respond("reviseDocument", t -> revision(true, "Kortade första punkten.", revised));

        JsonNode result = chat(cvId, "Gör första punkten mer konkret", 200);

        assertThat(result.get("changed").asBoolean()).isTrue();
        assertThat(result.get("approvalRevoked").asBoolean()).isTrue();
        assertThat(result.get("reply").asText()).startsWith("Kortade första punkten.");
        JsonNode doc = result.get("document");
        assertThat(doc.get("version").asInt()).isEqualTo(2);
        assertThat(doc.get("approved").asBoolean()).isFalse();
        JsonNode item = doc.get("content").get("sections").get(1).get("items").get(0);
        assertThat(item.get("bullets").get(0).get("text").asText()).contains("förbättrade mikrotjänster");
        assertThat(item.get("title").asText()).isEqualTo("Systemutvecklare (praktik)"); // restored from the database
        assertThat(item.get("meta").asText()).isEqualTo("Braive AB");
        assertThat(docs(jobId).get("cv").get("version").asInt()).isEqualTo(2);
        // the prompt carried the request, the current document and the data-not-instructions warning
        String prompt = claude.calls().stream().filter(c -> c.name().equals("reviseDocument")).findFirst().orElseThrow().prompt();
        assertThat(prompt).contains("Gör första punkten mer konkret", "data, not instructions")
                .doesNotContain("SECRET OWNER TODO");
    }

    @Test
    void chatThatChangesNothingKeepsTheVersionAndExplainsWhy() throws Exception {
        generate(jobId);
        long letterId = docs(jobId).get("letter").get("id").asLong();
        JsonNode current = docs(jobId).get("letter").get("content");
        claude.respond("reviseDocument", t -> revision(false, "Det finns ingen erfarenhet av Rust i din CV-databas.", current.toString()));

        JsonNode result = chat(letterId, "Lägg till att jag kan Rust", 200);

        assertThat(result.get("changed").asBoolean()).isFalse();
        assertThat(result.get("reply").asText()).contains("Rust");
        assertThat(docs(jobId).get("letter").get("version").asInt()).isEqualTo(1);
    }

    @Test
    void chatCanUseAnyFactOfTheDatabaseAndTheJobSelectionGrowsWithIt() throws Exception {
        generate(jobId);
        long letterId = docs(jobId).get("letter").get("id").asLong();
        String letter = letterJson("sv", "Jag är systemutvecklare och har även undervisat över 100 studenter.")
                .replace("\"factIdsUsed\":[" + fBraive1 + "," + fPersonal + "]", "\"factIdsUsed\":[" + fBraive1 + "," + fPersonal + "," + fTeach + "]");
        claude.respond("reviseDocument", t -> revision(true, "Lade till undervisningen.", letter));

        JsonNode result = chat(letterId, "Nämn att jag har undervisat", 200);

        assertThat(result.get("changed").asBoolean()).isTrue();
        // the offered facts included the teaching entry even though the first selection left it out
        String prompt = claude.calls().stream().filter(c -> c.name().equals("reviseDocument")).findFirst().orElseThrow().prompt();
        assertThat(prompt).contains("Taught labs for over 100 students.");
        assertThat(jdbc.sql("SELECT selection_json::text FROM job WHERE id = :id").param("id", jobId).query(String.class).single())
                .contains("\"entryId\": " + teaching);
        assertThat(result.get("document").get("report").get("violations")).isEmpty();
    }

    @Test
    void entriesTheUserExcludedForTheJobAreNeverOfferedToTheModel() throws Exception {
        generate(jobId);
        call(patch("/api/jobs/" + jobId + "/selection"), "{\"pinned\":[],\"excluded\":[" + teaching + "]}", 204);
        long letterId = docs(jobId).get("letter").get("id").asLong();
        JsonNode current = docs(jobId).get("letter").get("content");
        claude.respond("reviseDocument", t -> revision(false, "Ok.", current.toString()));

        chat(letterId, "Lite kortare", 200);

        String prompt = claude.calls().stream().filter(c -> c.name().equals("reviseDocument")).findFirst().orElseThrow().prompt();
        assertThat(prompt).doesNotContain("Taught labs for over 100 students.").contains("Built microservices in Java and Kotlin.");
    }

    @Test
    void aCitationOfAnExcludedEntryIsNeverAcceptedEvenIfTheModelIgnoresTheExclusion() throws Exception {
        generate(jobId);
        call(patch("/api/jobs/" + jobId + "/selection"), "{\"pinned\":[],\"excluded\":[" + teaching + "]}", 204);
        long letterId = docs(jobId).get("letter").get("id").asLong();
        String sneaky = letterJson("sv", "Jag är systemutvecklare och har undervisat över 100 studenter.")
                .replace("\"factIdsUsed\":[" + fBraive1 + "," + fPersonal + "]", "\"factIdsUsed\":[" + fBraive1 + "," + fPersonal + "," + fTeach + "]");
        claude.respond("reviseDocument", t -> revision(true, "Lade till undervisningen.", sneaky));
        claude.respond("rewriteForViolations", t -> letterJson("sv", "Jag är systemutvecklare med erfarenhet av Java och Kotlin."));

        JsonNode result = chat(letterId, "Nämn undervisningen", 200);

        assertThat(jdbc.sql("SELECT selection_json::text FROM job WHERE id = :id").param("id", jobId).query(String.class).single())
                .doesNotContain("\"entryId\": " + teaching);
        // the validator flagged the citation, the one rewrite removed it, and nothing outside the selection was saved
        assertThat(claude.calls().stream().filter(c -> c.name().equals("rewriteForViolations"))).hasSize(1);
        assertThat(result.get("document").get("content").get("factIdsUsed").toString()).doesNotContain(String.valueOf(fTeach));
        assertThat(result.get("document").get("content").toString()).doesNotContain("över 100 studenter");
    }

    @Test
    void aRevisionThatBreaksTheWritingRulesGetsOneRewriteBeforeItIsSaved() throws Exception {
        generate(jobId);
        long cvId = docs(jobId).get("cv").get("id").asLong();
        String dashed = cvJson("sv", "Byggde mikrotjänster – i Java och Kotlin.", "Åtgärdade race conditions i loggningsflödena.");
        claude.respond("reviseDocument", t -> revision(true, "Ändrade.", dashed));
        claude.respond("rewriteForViolations", t -> cvJson("sv", "Byggde mikrotjänster i Java och Kotlin.", "Åtgärdade race conditions i loggningsflödena."));

        JsonNode result = chat(cvId, "Skriv om första punkten", 200);

        assertThat(claude.calls().stream().filter(c -> c.name().equals("rewriteForViolations"))).hasSize(1);
        assertThat(result.get("document").get("report").get("violations")).isEmpty();
        assertThat(result.get("document").get("content").toString()).doesNotContain("–");
    }

    @Test
    void chatRefusesAnEmptyRequestAndAMalformedAnswerChangesNothing() throws Exception {
        generate(jobId);
        long cvId = docs(jobId).get("cv").get("id").asLong();
        chat(cvId, "   ", 400);
        claude.respond("reviseDocument", t -> "{\"changed\":true,\"reply\":\"x\",\"document\":{\"language\":\"sv\"}}");

        JsonNode error = chat(cvId, "Ändra något", 500);

        assertThat(error.get("error").asText()).contains("nothing was changed");
        assertThat(docs(jobId).get("cv").get("version").asInt()).isEqualTo(1);
        call(post("/api/documents/999999/chat"), "{\"message\":\"hej\"}", 404);
    }

    // ---- selected applications: batch generation and the user's own "submitted" confirmation ----

    JsonNode awaitApplications(java.util.function.Predicate<JsonNode> done) throws Exception {
        long deadline = System.currentTimeMillis() + 40_000;
        JsonNode list;
        do {
            list = call(get("/api/applications"), null, 200);
            if (done.test(list)) {
                return list;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("applications did not settle: " + list);
    }

    JsonNode application(JsonNode list, long job) {
        for (JsonNode a : list) {
            if (a.get("jobId").asLong() == job) {
                return a;
            }
        }
        throw new AssertionError("no application for job " + job + " in " + list);
    }

    @Test
    void createDocumentsForSelectedJobsRunsAsOneBatchAndOneFailureDoesNotStopTheRest() throws Exception {
        long second = addJob("Java-utvecklare Backend", "Acme AB", "sv", "Vi söker backendutvecklare, FAILME.", runId);
        long third = addJob("Systemutvecklare", "Foo AB", "sv", "Vi söker en systemutvecklare.", runId);
        // the second ad's CV cannot be written
        claude.respond("writeCv", t -> {
            if (t.prompt().contains("FAILME")) {
                throw new se.caibirch.jobpilot.claude.ClaudeCallException("the model failed for this ad");
            }
            return cvJson("sv", "Byggde mikrotjänster i Java och Kotlin.", "Åtgärdade race conditions i loggningsflödena.");
        });

        JsonNode started = call(post("/api/applications/documents"),
                "{\"jobIds\":[" + jobId + "," + second + "," + third + "],\"runId\":" + runId + "}", 202);
        assertThat(started.get("items")).hasSize(3);

        JsonNode list = awaitApplications(l -> l.size() == 3 && java.util.stream.StreamSupport.stream(l.spliterator(), false)
                .allMatch(a -> a.get("generation") != null && !a.get("generation").isNull()
                        && Set.of("DONE", "FAILED").contains(a.get("generation").get("status").asText())));

        assertThat(application(list, jobId).get("generation").get("status").asText()).isEqualTo("DONE");
        assertThat(application(list, third).get("generation").get("status").asText()).isEqualTo("DONE");
        JsonNode failed = application(list, second);
        assertThat(failed.get("generation").get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("generation").get("message").asText()).contains("the model failed");
        assertThat(application(list, jobId).get("cv").get("version").asInt()).isEqualTo(1);
        assertThat(application(list, second).get("cv").isNull()).isTrue();
        assertThat(application(list, jobId).get("searchMode").asText()).isEqualTo("TARGETED");
    }

    /** Makes writeCv and writeLetter slow and records how many of them were running at the same moment. */
    java.util.concurrent.atomic.AtomicInteger slowWriters() {
        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Function<String, String> slow = json -> {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return json;
        };
        claude.respond("writeCv", t -> slow.apply(cvJson("sv", "Byggde mikrotjänster i Java och Kotlin.", "Åtgärdade race conditions i loggningsflödena.")));
        claude.respond("writeLetter", t -> slow.apply(letterJson("sv", "Jag är systemutvecklare med erfarenhet av Java och Kotlin.")));
        return peak;
    }

    @Test
    void theCvAndTheLetterOfOneJobAreWrittenAtTheSameTime() throws Exception {
        var peak = slowWriters();
        JsonNode run = generate(jobId);
        assertThat(run.get("run").get("status").asText()).as(run.get("run").get("message").asText()).isEqualTo("DONE");
        assertThat(peak.get()).as("writeCv and writeLetter overlapped").isGreaterThanOrEqualTo(2);
        assertThat(docs(jobId).get("cv").get("version").asInt()).isEqualTo(1);
        assertThat(docs(jobId).get("letter").get("version").asInt()).isEqualTo(1);
    }

    @Test
    void jobsOfOneBatchAreWrittenAtTheSameTime() throws Exception {
        long second = addJob("Java-utvecklare Backend", "Acme AB", "sv", "Vi söker backendutvecklare.", runId);
        long third = addJob("Systemutvecklare", "Foo AB", "sv", "Vi söker en systemutvecklare.", runId);
        var peak = slowWriters();

        call(post("/api/applications/documents"), "{\"jobIds\":[" + jobId + "," + second + "," + third + "],\"runId\":" + runId + "}", 202);
        awaitApplications(l -> l.size() == 3 && java.util.stream.StreamSupport.stream(l.spliterator(), false)
                .allMatch(a -> a.get("generation") != null && !a.get("generation").isNull()
                        && Set.of("DONE", "FAILED").contains(a.get("generation").get("status").asText())));

        assertThat(peak.get()).as("more than one job's writers ran together").isGreaterThanOrEqualTo(3);
    }

    @Test
    void selectingAJobTwiceKeepsOneApplicationAndRemovingItLeavesTheDocuments() throws Exception {
        call(post("/api/applications"), "{\"jobIds\":[" + jobId + "],\"runId\":" + runId + "}", 200);
        call(post("/api/applications"), "{\"jobIds\":[" + jobId + "],\"runId\":" + runId + "}", 200);
        assertThat(call(get("/api/applications"), null, 200)).hasSize(1);
        assertThat(call(get("/api/jobs/" + jobId), null, 200).get("row").get("status").asText()).isEqualTo("SHORTLISTED");

        generate(jobId);
        call(delete("/api/applications/" + jobId), null, 204);
        assertThat(call(get("/api/applications"), null, 200)).isEmpty();
        assertThat(docs(jobId).get("cv").isNull()).isFalse();
        call(delete("/api/applications/" + jobId), null, 404);
        call(post("/api/applications"), "{\"jobIds\":[999999]}", 404);
    }

    @Test
    void markingAnApplicationSubmittedRecordsTheDocumentsUsedAndCanBeUndone() throws Exception {
        generate(jobId);
        call(post("/api/applications"), "{\"jobIds\":[" + jobId + "],\"runId\":" + runId + "}", 200);

        JsonNode view = call(post("/api/applications/" + jobId + "/submitted"), null, 200);
        assertThat(view.get("submittedAt").isNull()).isFalse();
        assertThat(view.get("jobStatus").asText()).isEqualTo("APPLIED");
        assertThat(view.get("submittedDocs").get("cv").get("file").asText()).endsWith(".pdf");
        assertThat(view.get("submittedDocs").get("letter").get("version").asInt()).isEqualTo(1);

        String firstAt = view.get("submittedAt").asText();
        assertThat(call(post("/api/applications/" + jobId + "/submitted"), null, 200).get("submittedAt").asText()).isEqualTo(firstAt);

        JsonNode undone = call(delete("/api/applications/" + jobId + "/submitted"), null, 200);
        assertThat(undone.get("submittedAt").isNull()).isTrue();
        assertThat(undone.get("jobStatus").asText()).isEqualTo("FILLED");
    }

    @Test
    void generatesBothDocumentsFillsFactsFromTheDatabaseAndRendersPdfs() throws Exception {
        JsonNode run = generate(jobId);

        assertThat(run.get("run").get("status").asText()).as(run.get("run").get("message").asText()).isEqualTo("DONE");
        assertThat(run.get("run").get("kind").asText()).isEqualTo("DOCUMENTS");
        assertThat(run.get("run").get("jobId").asLong()).isEqualTo(jobId);
        JsonNode both = docs(jobId);
        JsonNode cv = both.get("cv");
        JsonNode letter = both.get("letter");
        assertThat(cv.get("version").asInt()).isEqualTo(1);
        assertThat(cv.get("approved").asBoolean()).isFalse();
        assertThat(cv.get("report").get("violations")).isEmpty();
        assertThat(letter.get("report").get("violations")).isEmpty();

        JsonNode experience = cv.get("content").get("sections").get(1).get("items").get(0);
        assertThat(experience.get("title").asText()).isEqualTo("Systemutvecklare (praktik)");
        assertThat(experience.get("meta").asText()).isEqualTo("Braive AB");
        assertThat(experience.get("location").asText()).isEqualTo("Stockholm");
        assertThat(experience.get("dateLabel").asText()).isEqualTo("2026");
        assertThat(cv.get("content").get("languagesLine").asText()).isEqualTo("Svenska (flytande) och engelska (modersmål)");
        // Education is always added, without bullets
        JsonNode education = cv.get("content").get("sections").get(2);
        assertThat(education.get("kind").asText()).isEqualTo("EDUCATION");
        assertThat(education.get("items").get(0).get("dateLabel").asText()).isEqualTo("2024-2026");
        // Laborationslärare was not selected and leaves a 3-year gap, so the CV says so
        assertThat(cv.get("content").get("gapNote").asText()).isEqualTo("Övrig anställning 2018-2021, referenser lämnas på begäran");

        // facts for the hover citations
        assertThat(cv.get("facts").get(String.valueOf(fBraive1)).get("textSv").asText()).startsWith("Byggde mikrotjänster");

        byte[] cvPdf = mvc.perform(get("/api/documents/" + cv.get("id").asLong() + "/pdf")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(pdfText(cvPdf)).contains("CAI BIRCH", "Systemutvecklare (praktik) | Braive AB", "Övrig anställning 2018-2021");
        assertThat(Files.exists(dataDir.resolve("documents/job-" + jobId + "/CV_Cai_Birch_Comstream.pdf"))).isTrue();
        assertThat(Files.exists(dataDir.resolve("documents/job-" + jobId + "/Personligt_brev_Cai_Birch_Comstream.pdf"))).isTrue();
        assertThat(pdfText(mvc.perform(get("/api/documents/" + letter.get("id").asLong() + "/pdf")).andReturn().getResponse().getContentAsByteArray()))
                .contains("Ansökan: Junior Java-utvecklare", "Vänliga hälsningar,");
        assertThat(call(get("/api/jobs/" + jobId + "/documents"), null, 200).get("selection").get("entries")).hasSize(3);
    }

    @Test
    void promptsCarryTheRulesAndFactsButNeverTheOwnersNotes() throws Exception {
        generate(jobId);

        for (var task : claude.calls()) {
            assertThat(task.prompt()).as(task.name()).doesNotContain("SECRET OWNER TODO");
        }
        String cvPrompt = claude.calls().stream().filter(c -> c.name().equals("writeCv")).findFirst().orElseThrow().prompt();
        assertThat(cvPrompt).contains("Never use an em-dash or an en-dash", "Never describe the candidate as newly qualified",
                "Byggde mikrotjänster i Java och Kotlin.", "\"entryId\":" + braive, "Swedish")
                .doesNotContain("Spends free time out on the water");
        String letterPrompt = claude.calls().stream().filter(c -> c.name().equals("writeLetter")).findFirst().orElseThrow().prompt();
        assertThat(letterPrompt).contains("Spends free time out on the water", "No Angular", "Tack för att ni överväger min ansökan",
                "Jag heter").contains("never its sentences");
    }

    @Test
    void aViolationTriggersExactlyOneRewriteWhichFixesIt() throws Exception {
        claude.respond("writeCv", t -> cvJson("sv", "Byggde mikrotjänster – i Java och Kotlin.", "Åtgärdade race conditions i loggningsflödena."));
        claude.respond("rewriteForViolations", t -> cvJson("sv", "Byggde mikrotjänster i Java och Kotlin.", "Åtgärdade race conditions i loggningsflödena."));

        JsonNode run = generate(jobId);

        assertThat(run.get("run").get("status").asText()).isEqualTo("DONE");
        assertThat(claude.calls().stream().filter(c -> c.name().equals("rewriteForViolations")).count()).isEqualTo(1);
        String rewritePrompt = claude.calls().stream().filter(c -> c.name().equals("rewriteForViolations")).findFirst().orElseThrow().prompt();
        assertThat(rewritePrompt).contains("\"code\":\"dash\"", "sections[1].items[0].bullets[0]");
        JsonNode cv = docs(jobId).get("cv");
        assertThat(cv.get("report").get("violations")).isEmpty();
        assertThat(cv.get("content").toString()).doesNotContain("–");
        // the rewrite cannot change Java-owned fields
        assertThat(cv.get("content").get("sections").get(1).get("items").get(0).get("meta").asText()).isEqualTo("Braive AB");
    }

    @Test
    void ifTheRewriteStillFailsTheDocumentIsKeptWithItsViolationsAndCannotBeApproved() throws Exception {
        String bad = cvJson("sv", "Byggde mikrotjänster – i Java och Kotlin.", "Åtgärdade race conditions i loggningsflödena.");
        claude.respond("writeCv", t -> bad);
        claude.respond("rewriteForViolations", t -> bad);

        JsonNode run = generate(jobId);

        assertThat(run.get("run").get("status").asText()).isEqualTo("DONE");
        assertThat(run.get("run").get("message").asText()).contains("problems to fix");
        assertThat(claude.calls().stream().filter(c -> c.name().equals("rewriteForViolations")).count()).as("only once").isEqualTo(1);
        JsonNode cv = docs(jobId).get("cv");
        assertThat(codes(cv)).containsExactly("dash");
        assertThat(cv.get("report").get("violations").get(0).get("where").asText()).isEqualTo("sections[1].items[0].bullets[0]");
        JsonNode refused = call(post("/api/documents/" + cv.get("id").asLong() + "/approve"), null, 409);
        assertThat(refused.get("report").get("violations")).hasSize(1);
        assertThat(docs(jobId).get("cv").get("approved").asBoolean()).isFalse();

        // the user fixes the text by hand: new version, re-rendered, then approval works
        ObjectNode fixed = (ObjectNode) cv.get("content").deepCopy();
        ((ObjectNode) fixed.get("sections").get(1).get("items").get(0).get("bullets").get(0)).put("text", "Byggde mikrotjänster i Java och Kotlin.");
        JsonNode updated = call(put("/api/documents/" + cv.get("id").asLong()), fixed.toString(), 200);
        assertThat(updated.get("version").asInt()).isEqualTo(2);
        assertThat(updated.get("report").get("violations")).isEmpty();
        call(post("/api/documents/" + cv.get("id").asLong() + "/approve"), null, 200);
        assertThat(docs(jobId).get("cv").get("approved").asBoolean()).isTrue();
    }

    @Test
    void editingValidatesRerendersAndRevokesApproval() throws Exception {
        generate(jobId);
        JsonNode cv = docs(jobId).get("cv");
        long id = cv.get("id").asLong();
        call(post("/api/documents/" + id + "/approve"), null, 200);

        ObjectNode edited = (ObjectNode) cv.get("content").deepCopy();
        edited.put("headline", "Backendutvecklare <b>Java</b> — Spring");
        JsonNode updated = call(put("/api/documents/" + id), edited.toString(), 200);

        assertThat(updated.get("version").asInt()).isEqualTo(2);
        assertThat(updated.get("approved").asBoolean()).as("an edit revokes approval").isFalse();
        assertThat(codes(updated)).contains("dash");
        byte[] pdf = mvc.perform(get("/api/documents/" + id + "/pdf")).andReturn().getResponse().getContentAsByteArray();
        assertThat(pdfText(pdf)).contains("Backendutvecklare <b>Java</b>");
        call(put("/api/documents/" + id), "{\"headline\": 5, \"sections\": \"x\"}", 400);
    }

    @Test
    void approvingBothDocumentsMarksTheJobDocsReady() throws Exception {
        generate(jobId);
        long cvId = docs(jobId).get("cv").get("id").asLong();
        long letterId = docs(jobId).get("letter").get("id").asLong();

        call(post("/api/documents/" + cvId + "/approve"), null, 200);
        assertThat(jdbc.sql("SELECT status FROM job WHERE id = :id").param("id", jobId).query(String.class).single()).isEqualTo("NEW");
        call(post("/api/documents/" + letterId + "/approve"), null, 200);

        assertThat(jdbc.sql("SELECT status FROM job WHERE id = :id").param("id", jobId).query(String.class).single()).isEqualTo("DOCS_READY");
    }

    @Test
    void editingOrRegeneratingRevokesApprovalAndTheJobLeavesDocsReady() throws Exception {
        generate(jobId);
        long cvId = docs(jobId).get("cv").get("id").asLong();
        long letterId = docs(jobId).get("letter").get("id").asLong();
        call(post("/api/documents/" + cvId + "/approve"), null, 200);
        call(post("/api/documents/" + letterId + "/approve"), null, 200);
        assertThat(jdbc.sql("SELECT status FROM job WHERE id = :id").param("id", jobId).query(String.class).single()).isEqualTo("DOCS_READY");

        ObjectNode edited = (ObjectNode) docs(jobId).get("cv").get("content").deepCopy();
        edited.put("headline", "Backendutvecklare | Java");
        call(put("/api/documents/" + cvId), edited.toString(), 200);
        assertThat(jdbc.sql("SELECT status FROM job WHERE id = :id").param("id", jobId).query(String.class).single()).isEqualTo("SHORTLISTED");

        call(post("/api/documents/" + cvId + "/approve"), null, 200);
        assertThat(jdbc.sql("SELECT status FROM job WHERE id = :id").param("id", jobId).query(String.class).single()).isEqualTo("DOCS_READY");
        generate(jobId);
        assertThat(jdbc.sql("SELECT status FROM job WHERE id = :id").param("id", jobId).query(String.class).single()).isEqualTo("SHORTLISTED");
    }

    @Test
    void regeneratingBumpsTheVersionAndReplacesApproval() throws Exception {
        generate(jobId);
        call(post("/api/documents/" + docs(jobId).get("cv").get("id").asLong() + "/approve"), null, 200);

        generate(jobId);

        JsonNode cv = docs(jobId).get("cv");
        assertThat(cv.get("version").asInt()).isEqualTo(2);
        assertThat(cv.get("approved").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM document").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void excludedEntriesNeverReachTheDocumentsAndPinnedOnesAlwaysDo() throws Exception {
        call(patch("/api/jobs/" + jobId + "/selection"), "{\"pinned\":[" + teaching + "],\"excluded\":[" + personal + "]}", 204);
        claude.respond("selectFacts", t -> selection(braive, fBraive1));
        claude.respond("writeLetter", t -> letterJson("sv", "Jag är systemutvecklare med erfarenhet av Java.")
                .replace("," + fPersonal + "]", "]")); // the personal entry is excluded, so the letter must not cite it

        JsonNode run = generate(jobId);
        assertThat(run.get("run").get("status").asText()).as(run.get("run").get("message").asText()).isEqualTo("DONE");

        String selectPrompt = claude.calls().stream().filter(c -> c.name().equals("selectFacts")).findFirst().orElseThrow().prompt();
        assertThat(selectPrompt).contains("Entries the user pinned (must be included): [" + teaching + "]")
                .contains("Entries the user excluded (must not be included): [" + personal + "]").doesNotContain("Spends free time");
        JsonNode selection = docs(jobId).get("selection").get("entries");
        List<Long> entryIds = new ArrayList<>();
        selection.forEach(e -> entryIds.add(e.get("entryId").asLong()));
        assertThat(entryIds).contains(braive, teaching, yh).doesNotContain(personal);
        String cvPrompt = claude.calls().stream().filter(c -> c.name().equals("writeCv")).findFirst().orElseThrow().prompt();
        assertThat(cvPrompt).contains("Höll laborationer för över 100 studenter.");
        assertThat(docs(jobId).get("cv").get("content").path("gapNote").asText("")).as("the pinned job closes the gap").isEmpty();
        assertThat(docs(jobId).get("pinned").get(0).asLong()).isEqualTo(teaching);
    }

    @Test
    void citingAFactOfAnUnselectedEntryFailsValidation() throws Exception {
        String sneaky = cvJson("sv", "Byggde mikrotjänster i Java och Kotlin.", "Höll laborationer.")
                .replace("[" + fBraive2 + "]", "[" + fTeach + "]");
        claude.respond("writeCv", t -> sneaky);
        claude.respond("rewriteForViolations", t -> sneaky);

        generate(jobId);

        assertThat(codes(docs(jobId).get("cv"))).contains("unselected_entry");
        call(post("/api/documents/" + docs(jobId).get("cv").get("id").asLong() + "/approve"), null, 409);
    }

    @Test
    void anEnglishAdGetsEnglishDocumentsAndFileNames() throws Exception {
        long en = addJob("Software Developer", "Harbourline Logistics AB", "en", "We are looking for a developer with Java experience.", runId);
        claude.respond("writeCv", t -> cvJson("en", "Built microservices in Java and Kotlin.", "Fixed race conditions in the logging flows."));
        claude.respond("writeLetter", t -> letterJson("en", "I am a Java developer with experience in microservices."));

        JsonNode run = generate(en);

        assertThat(run.get("run").get("status").asText()).as(run.get("run").get("message").asText()).isEqualTo("DONE");
        JsonNode cv = docs(en).get("cv");
        assertThat(cv.get("language").asText()).isEqualTo("en");
        assertThat(cv.get("content").get("languagesLine").asText()).isEqualTo("English (native) and Swedish (fluent)");
        assertThat(cv.get("content").get("gapNote").asText()).isEqualTo("Other employment 2018-2021, available on request");
        assertThat(cv.get("content").get("sections").get(1).get("items").get(0).get("title").asText()).isEqualTo("Software Developer (intern)");
        assertThat(Files.exists(dataDir.resolve("documents/job-" + en + "/Cover_letter_Cai_Birch_Harbourline_Logistics.pdf"))).isTrue();
        String letterPrompt = claude.calls().stream().filter(c -> c.name().equals("writeLetter")).reduce((a, b) -> b).orElseThrow().prompt();
        assertThat(letterPrompt).contains("English").contains("Hello,").contains("Kind regards,").contains("I'm an operations coordinator who has spent six years")
                .doesNotContain("Tack för att ni överväger");
    }

    @Test
    void aDocumentInTheWrongLanguageFails() throws Exception {
        String wrong = cvJson("en", "Built microservices in Java and Kotlin.", "Fixed race conditions in the logging flows.");
        claude.respond("writeCv", t -> wrong);
        claude.respond("rewriteForViolations", t -> wrong);

        generate(jobId);

        assertThat(codes(docs(jobId).get("cv"))).contains("wrong_language");
    }

    @Test
    void aLetterThatNamesAnExampleLettersEmployerFails() throws Exception {
        String bad = letterJson("sv", "Jag vill jobba på Norrvik för att jag gillar Java.");
        claude.respond("writeLetter", t -> bad);
        claude.respond("rewriteForViolations", t -> bad);

        generate(jobId);

        assertThat(codes(docs(jobId).get("letter"))).contains("foreign_employer");
    }

    @Test
    void newGraduateWordingIsAllowedWhenTheAdAsksForIt() throws Exception {
        long grad = addJob("Trainee", "Acme AB", "sv", "Vi söker dig som är nyexaminerad.", runId);
        claude.respond("writeLetter", t -> letterJson("sv", "Jag är nyexaminerad systemutvecklare med erfarenhet av Java."));

        generate(grad);
        assertThat(docs(grad).get("letter").get("report").get("violations")).isEmpty();

        claude.respond("writeLetter", t -> letterJson("sv", "Jag är nyexaminerad systemutvecklare med erfarenhet av Java."));
        claude.respond("rewriteForViolations", t -> letterJson("sv", "Jag är nyexaminerad systemutvecklare med erfarenhet av Java."));
        generate(jobId);
        assertThat(codes(docs(jobId).get("letter"))).containsExactly("new_graduate");
    }

    @Test
    void generationNeedsASearchRunAndAKnownJob() throws Exception {
        long orphan = jdbc.sql("INSERT INTO job (source, title, dedupe_key) VALUES ('WEB', 'Orphan', 'orphan') RETURNING id")
                .query(Long.class).single();

        call(post("/api/jobs/" + orphan + "/documents"), "{}", 400);
        call(post("/api/jobs/99999/documents"), "{}", 404);
        call(get("/api/documents/99999"), null, 404);
    }

    @Test
    void aFailedGenerationStepFailsTheRunWithTheReason() throws Exception {
        claude.respond("writeLetter", t -> {
            throw new se.caibirch.jobpilot.claude.ClaudeCallException("Claude CLI exited with code 1");
        });

        JsonNode run = generate(jobId);

        assertThat(run.get("run").get("status").asText()).isEqualTo("FAILED");
        assertThat(run.get("run").get("message").asText()).contains("exited with code 1");
    }

    @Test
    void documentRunsStayOutOfTheSearchRunList() throws Exception {
        generate(jobId);

        assertThat(call(get("/api/runs"), null, 200).toString()).doesNotContain("DOCUMENTS");
        assertThat(call(get("/api/documents"), null, 200)).hasSize(1);
        assertThat(call(get("/api/documents"), null, 200).get(0).get("employer").asText()).isEqualTo("Comstream AB");
    }

    @Test
    void pinningAndExcludingTheSameEntryIsRejected() throws Exception {
        call(patch("/api/jobs/" + jobId + "/selection"), "{\"pinned\":[" + braive + "],\"excluded\":[" + braive + "]}", 400);
        call(patch("/api/jobs/" + jobId + "/selection"), "{\"pinned\":[999999],\"excluded\":[]}", 400);
        call(patch("/api/jobs/99999/selection"), "{\"pinned\":[],\"excluded\":[]}", 404);
        assertThat(Set.of(1)).isNotEmpty();
    }
}
