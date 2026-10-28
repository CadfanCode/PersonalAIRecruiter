package se.caibirch.jobpilot.pipeline;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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

import se.caibirch.jobpilot.claude.FakeClaudeRunner;
import se.caibirch.jobpilot.support.PostgresTestConfig;

/**
 * Discover roles end to end against real PostgreSQL, a WireMock JobTech and a fake Claude: suggestions from the whole CV,
 * the user's choice (with a role of their own), one search and profile per role, a merged ranked list tagged with its roles,
 * the scoring cap, and the role profile reaching tailoring and the aktivitetsrapport. Nothing reaches a live API or the CLI.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, DiscoverIT.Fakes.class})
class DiscoverIT {

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

    static final String BASE = """
            {"roleSummary":"Whatever the user typed","titles":["x"],"synonyms":[],"skillsWanted":[],
             "seniority":"any","acceptExperienceUpToYears":3,
             "locations":[{"name":"Stockholm","municipality":"Stockholm","radiusKm":0}],
             "remoteOk":false,"languages":["sv","en"],"maxAgeDays":7,"employmentTypes":["heltid"],
             "exclusions":[],"yrkesrollForAktivitetsrapport":"Systemutvecklare/Programmerare",
             "relevantTags":[],"jobtechQueries":["base"]}""";

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

    static String roleFields(String summary, String yrkesroll, String query) {
        return """
                {"roleSummary":"%s","titles":["%s"],"synonyms":[],"skillsWanted":[],"seniority":"any","acceptExperienceUpToYears":3,
                 "exclusions":[],"yrkesrollForAktivitetsrapport":"%s","relevantTags":["teaching","not-a-real-tag"],"jobtechQueries":["%s"]}"""
                .formatted(summary, summary, yrkesroll, query);
    }

    static String role(String title, String category, int score, String summary, String yrkesroll, String query) {
        return """
                {"title":"%s","category":"%s","matchScore":%d,"reasoning":"Why it fits.","gaps":"A gap.","profile":%s}"""
                .formatted(title, category, score, roleFields(summary, yrkesroll, query));
    }

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE job, run_job, pipeline_run, search_profile, job_score, cv_entry, profile, tag, skill,
                         known_gap, answers_memory RESTART IDENTITY CASCADE""").update();
        jdbc.sql("INSERT INTO tag (name) VALUES ('teaching')").update();
        claude.calls().clear();
        claude.respond("buildSearchProfile", BASE);
        claude.respond("suggestRoles", "{\"roles\":[" + String.join(",",
                role("Javautvecklare", "RELATED", 85, "Java developer role", "Systemutvecklare/Programmerare", "java utvecklare"),
                role("Lärare i idrott och hälsa", "OUTSIDE_FIELD", 72, "Teaching role", "Grundskollärare", "idrottslärare"),
                role("Säkerhetsrådgivare", "HYBRID", 50, "Security advisor role", "Säkerhetsrådgivare", "säkerhet"),
                role("javautvecklare", "RELATED", 80, "Duplicate of the first", "x", "dup")) + "]}");
        claude.respond("profileRoles", "{\"roles\":[{\"title\":\"Behandlingsassistent\",\"profile\":"
                + roleFields("Treatment assistant role", "Behandlingsassistent", "behandlingsassistent") + "}]}");
        claude.respond("scoreJob", task -> {
            String p = task.prompt();
            if (p.contains("Teaching role")) {
                return score(40, 25, 15, "Teaching fit");
            }
            if (p.contains("Treatment assistant role")) {
                return score(30, 20, 10, "Care fit");
            }
            return score(45, 28, 18, "Java fit");
        });
        wm.resetAll();
        wm.stubFor(WireMock.get(urlPathEqualTo("/v1/taxonomy/main/concepts")).willReturn(json(
                "[{\"taxonomy/id\":\"AvNB_uwa_6n6\",\"taxonomy/preferred-label\":\"Stockholm\"}]")));
        stubSearch("java utvecklare", hit("j1", "Javautvecklare", "Comstream AB", 0.9), hit("shared", "Allround IT och idrott", "Skolan AB", 0.4));
        stubSearch("idrottslärare", hit("t1", "Idrottslärare", "Nacka skola", 0.8), hit("shared", "Allround IT och idrott", "Skolan AB", 0.95),
                hit("t2", "Gymnasielärare", "Gymnasiet AB", 0.7));
        stubSearch("behandlingsassistent", hit("b1", "Behandlingsassistent", "Vården AB", 0.6));
        stubSearch("säkerhet", hit("s1", "Säkerhetsrådgivare", "Skydd AB", 0.6));
    }

    // ---- helpers ----

    static String score(int skills, int exp, int elig, String fit) {
        return "{\"skills\":" + skills + ",\"skillsMax\":50,\"experience\":" + exp + ",\"experienceMax\":30,\"eligibility\":" + elig
                + ",\"eligibilityMax\":20,\"asksForNewGraduates\":false,\"experienceRequiredText\":\"\",\"language\":\"sv\",\"fit\":\""
                + fit + "\",\"gaps\":\"None\",\"dealBreakers\":[]}";
    }

    static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }

    static String hit(String id, String title, String employer, double relevance) {
        ZoneId se = ZoneId.of("Europe/Stockholm");
        String published = LocalDateTime.ofInstant(Instant.now().minusSeconds(86_400), se).withNano(0).toString();
        String deadline = LocalDateTime.ofInstant(Instant.now().plusSeconds(300_000), se).withNano(0).toString();
        return """
                {"id":"%s","headline":"%s","relevance":%s,"removed":false,"webpage_url":"https://platsbanken/%s",
                 "application_deadline":"%s","publication_date":"%s",
                 "description":{"text":"Vi söker dig som vill arbeta som %s."},
                 "employer":{"name":"%s","workplace":"%s"},"working_hours_type":{"label":"Heltid"},
                 "application_details":{"url":"https://apply/%s"},
                 "workplace_address":{"municipality":"Stockholm","city":"Stockholm"}}"""
                .formatted(id, title, relevance, id, deadline, published, title, employer, employer, id);
    }

    static void stubSearch(String query, String... hits) {
        wm.stubFor(WireMock.get(urlPathEqualTo("/search")).withQueryParam("q", equalTo(query)).willReturn(json(
                "{\"total\":{\"value\":" + hits.length + "},\"hits\":[" + String.join(",", hits) + "]}")));
    }

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

    long startDiscover(String extra) throws Exception {
        long id = call(post("/api/runs"), "{\"prompt\":\"Stockholm\",\"mode\":\"DISCOVER\"" + extra + "}", 201).get("id").asLong();
        JsonNode view = await(id, "WAITING_FOR_USER", "FAILED");
        assertThat(view.get("run").get("status").asText()).as(view.get("run").get("message").asText()).isEqualTo("WAITING_FOR_USER");
        return id;
    }

    /** The roles as the user sends them back: everything ticked, plus one role of their own. */
    String allPlusCustom(JsonNode roles) {
        List<String> edits = new ArrayList<>();
        roles.forEach(r -> edits.add("{\"id\":" + r.get("id") + ",\"title\":\"" + r.get("title").asText() + "\",\"selected\":true}"));
        edits.add("{\"title\":\"Behandlingsassistent\",\"selected\":true}");
        return "[" + String.join(",", edits) + "]";
    }

    JsonNode jobs(long runId) throws Exception {
        return call(get("/api/runs/" + runId + "/jobs"), null, 200);
    }

    JsonNode jobByTitle(JsonNode jobs, String title) {
        for (JsonNode j : jobs) {
            if (j.get("title").asText().equals(title)) {
                return j;
            }
        }
        throw new AssertionError("no job " + title + " in " + jobs);
    }

    // ---- tests ----

    @Test
    void suggestsRolesFromTheWholeCvAndPausesForTheUsersChoice() throws Exception {
        long id = startDiscover("");

        JsonNode view = call(get("/api/runs/" + id), null, 200);
        assertThat(view.get("run").get("step").asText()).isEqualTo("SUGGEST");
        assertThat(view.get("run").get("options").get("mode").asText()).isEqualTo("DISCOVER");
        JsonNode roles = call(get("/api/runs/" + id + "/roles"), null, 200);
        assertThat(roles).hasSize(3); // the duplicate title was dropped
        assertThat(roles.get(0).get("title").asText()).isEqualTo("Javautvecklare");
        assertThat(roles.get(1).get("category").asText()).isEqualTo("OUTSIDE_FIELD");
        assertThat(roles.get(1).get("reasoning").asText()).isNotBlank();
        assertThat(roles.get(1).get("gaps").asText()).isNotBlank();
        // strong matches start ticked, the weak one does not
        assertThat(roles.get(0).get("selected").asBoolean()).isTrue();
        assertThat(roles.get(1).get("selected").asBoolean()).isTrue();
        assertThat(roles.get(2).get("selected").asBoolean()).isFalse();
        // the prompt gave Claude the whole CV digest and the "think widely" rule only in aggressive mode
        String prompt = claude.calls().stream().filter(c -> c.name().equals("suggestRoles")).findFirst().orElseThrow().prompt();
        assertThat(prompt).contains("Only include roles with a matchScore of 55 or more").contains("OUTSIDE_FIELD");
        assertThat(jobs(id)).isEmpty();
    }

    @Test
    void aggressiveModeAsksForMoreRolesAndWiderAdsAndALowerThreshold() throws Exception {
        long id = startDiscover(",\"aggressive\":true");

        String prompt = claude.calls().stream().filter(c -> c.name().equals("suggestRoles")).findFirst().orElseThrow().prompt();
        assertThat(prompt).contains("Suggest 24 different role types").contains("moderate match (40 or more)");
        JsonNode roles = call(get("/api/runs/" + id + "/roles"), null, 200);
        assertThat(roles.get(2).get("selected").asBoolean()).isTrue(); // 50 is below the normal bar of 60 but above the aggressive one
        // the base profile's age limit is widened from 7 to 14 days
        assertThat(call(get("/api/runs/" + id), null, 200).get("profile").get("maxAgeDays").asInt()).isEqualTo(14);
    }

    @Test
    void searchesEveryChosenRoleIncludingOneTheUserTypedAndMergesIntoOneTaggedRankedList() throws Exception {
        long id = startDiscover("");
        JsonNode roles = call(get("/api/runs/" + id + "/roles"), null, 200);

        JsonNode saved = call(put("/api/runs/" + id + "/roles"), allPlusCustom(roles), 200);
        assertThat(saved).hasSize(4);
        assertThat(saved.get(3).get("title").asText()).isEqualTo("Behandlingsassistent");
        assertThat(saved.get(3).get("userAdded").asBoolean()).isTrue();
        call(post("/api/runs/" + id + "/continue"), null, 200);
        JsonNode done = await(id, "DONE", "FAILED");
        assertThat(done.get("run").get("status").asText()).as(done.get("run").get("message").asText()).isEqualTo("DONE");

        JsonNode rows = jobs(id);
        assertThat(rows).hasSize(6); // j1, shared (found twice), t1, t2, b1, s1
        JsonNode shared = jobByTitle(rows, "Allround IT och idrott");
        assertThat(shared.get("roleType").asText()).isEqualTo("Lärare i idrott och hälsa"); // its best relevance (0.95 vs 0.4)
        List<String> tags = new ArrayList<>();
        shared.get("roleTypes").forEach(t -> tags.add(t.asText()));
        assertThat(tags).containsExactly("Javautvecklare", "Lärare i idrott och hälsa");
        assertThat(shared.get("score").get("fit").asText()).isEqualTo("Teaching fit");
        assertThat(jobByTitle(rows, "Javautvecklare").get("score").get("fit").asText()).isEqualTo("Java fit");
        assertThat(jobByTitle(rows, "Behandlingsassistent").get("score").get("fit").asText()).isEqualTo("Care fit");
        assertThat(jobByTitle(rows, "Behandlingsassistent").get("roleType").asText()).isEqualTo("Behandlingsassistent");
        assertThat(rows.get(0).get("rank").asInt()).isEqualTo(1); // one merged ranking

        // one profile per role, built on the user's base profile: the base locations, the role's own queries and yrkesroll
        assertThat(jdbc.sql("SELECT count(*) FROM role_suggestion WHERE run_id = :r AND search_profile_id IS NOT NULL")
                .param("r", id).query(Long.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT profile_json->>'yrkesrollForAktivitetsrapport' FROM search_profile WHERE prompt = 'Lärare i idrott och hälsa'")
                .query(String.class).single()).isEqualTo("Grundskollärare");
        assertThat(jdbc.sql("SELECT profile_json->'relevantTags'::text FROM search_profile WHERE prompt = 'Lärare i idrott och hälsa'")
                .query(String.class).single()).isEqualTo("[\"teaching\"]"); // the tag that does not exist was dropped
        assertThat(jdbc.sql("SELECT profile_json->'locations'->0->>'municipality' FROM search_profile WHERE prompt = 'Behandlingsassistent'")
                .query(String.class).single()).isEqualTo("Stockholm");
        // the custom role needed exactly one batched Claude call
        assertThat(claude.calls().stream().filter(c -> c.name().equals("profileRoles"))).hasSize(1);
        // the suggestions stay with the run, with what each role found
        JsonNode after = call(get("/api/runs/" + id + "/roles"), null, 200);
        assertThat(after.get(0).get("searched").asBoolean()).isTrue();
        assertThat(after.get(1).get("jobCount").asInt()).isEqualTo(3);
    }

    @Test
    void theScoringLimitSpreadsTheBudgetAcrossTheRoles() throws Exception {
        long id = startDiscover(",\"limit\":3");
        JsonNode roles = call(get("/api/runs/" + id + "/roles"), null, 200);
        call(put("/api/runs/" + id + "/roles"), allPlusCustom(roles), 200);
        call(post("/api/runs/" + id + "/continue"), null, 200);
        JsonNode done = await(id, "DONE", "FAILED");

        assertThat(done.get("run").get("message").asText()).contains("scoring 3").contains("not scored (limit 3)");
        long scored = claude.calls().stream().filter(c -> c.name().equals("scoreJob")).count();
        assertThat(scored).isEqualTo(3);
        List<String> scoredRoles = new ArrayList<>();
        jobs(id).forEach(j -> {
            if (!j.get("score").isNull()) {
                scoredRoles.add(j.get("roleType").asText());
            }
        });
        // one ad from each of three different roles, not three ads of the role with the most hits
        assertThat(scoredRoles).containsExactlyInAnyOrder("Javautvecklare", "Lärare i idrott och hälsa", "Säkerhetsrådgivare");
        assertThat(call(post("/api/runs"), "{\"prompt\":\"x\",\"mode\":\"DISCOVER\",\"limit\":151}", 400).get("error").asText()).contains("150");
    }

    @Test
    void theRoleProfileReachesTailoringAndTheAktivitetsrapport() throws Exception {
        long id = startDiscover("");
        call(post("/api/runs/" + id + "/continue"), null, 200);
        await(id, "DONE", "FAILED");
        JsonNode rows = jobs(id);
        long teacherJob = jobByTitle(rows, "Idrottslärare").get("id").asLong();

        JsonNode applications = call(post("/api/applications"), "{\"jobIds\":[" + teacherJob + "],\"runId\":" + id + "}", 200);
        assertThat(applications.get(0).get("searchMode").asText()).isEqualTo("DISCOVER");
        assertThat(applications.get(0).get("roleType").asText()).isEqualTo("Lärare i idrott och hälsa");
        assertThat(applications.get(0).get("score").asInt()).isEqualTo(80);

        // documents are tailored with the role's profile, not the run's base profile (which has no scores)
        call(post("/api/applications/documents"), "{\"jobIds\":[" + teacherJob + "],\"runId\":" + id + "}", 202);
        long rolesProfile = jdbc.sql("SELECT search_profile_id FROM role_suggestion WHERE run_id = :r AND title = 'Lärare i idrott och hälsa'")
                .param("r", id).query(Long.class).single();
        long docsProfile = jdbc.sql("SELECT search_profile_id FROM pipeline_run WHERE kind = 'DOCUMENTS' AND job_id = :j")
                .param("j", teacherJob).query(Long.class).single();
        assertThat(docsProfile).isEqualTo(rolesProfile);
        assertThat(docsProfile).isNotEqualTo(call(get("/api/runs/" + id), null, 200).get("run").get("searchProfileId").asLong());

        // the yrkesrollen typed into the aktivitetsrapport is the role's own
        call(post("/api/applications/" + teacherJob + "/submitted"), null, 200);
        JsonNode plan = call(get("/api/aktivitet/plan").param("jobIds", String.valueOf(teacherJob)), null, 200);
        assertThat(plan.get(0).get("entry").get("yrkesroll").asText()).isEqualTo("Grundskollärare");
    }

    @Test
    void rolesCanOnlyBeChangedWhileWaitingAndAtLeastOneMustBeChosen() throws Exception {
        long id = startDiscover("");
        JsonNode roles = call(get("/api/runs/" + id + "/roles"), null, 200);
        StringBuilder none = new StringBuilder("[");
        roles.forEach(r -> none.append(none.length() > 1 ? "," : "").append("{\"id\":").append(r.get("id")).append(",\"selected\":false}"));
        call(put("/api/runs/" + id + "/roles"), none + "]", 200);
        call(post("/api/runs/" + id + "/continue"), null, 409);
        call(put("/api/runs/" + id + "/roles"), "[{\"id\":999999,\"selected\":true}]", 404);

        call(put("/api/runs/" + id + "/roles"), "[{\"id\":" + roles.get(0).get("id") + ",\"selected\":true}]", 200);
        call(post("/api/runs/" + id + "/continue"), null, 200);
        await(id, "DONE", "FAILED");
        call(put("/api/runs/" + id + "/roles"), "[]", 409);
    }
}
