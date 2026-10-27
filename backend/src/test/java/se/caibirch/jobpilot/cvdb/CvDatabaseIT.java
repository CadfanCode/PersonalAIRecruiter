package se.caibirch.jobpilot.cvdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import se.caibirch.jobpilot.support.PostgresTestConfig;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class CvDatabaseIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE cv_entry, profile, tag, skill, known_gap, answers_memory RESTART IDENTITY CASCADE").update();
    }

    String seedJson() throws IOException {
        Path p = Path.of("../seed/cv_database_seed.json");
        if (!Files.exists(p)) {
            p = Path.of("seed/cv_database_seed.json");
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    JsonNode getJson(String url) throws Exception {
        return mapper.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    JsonNode send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String body, int status)
            throws Exception {
        String out = mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return out.isBlank() ? null : mapper.readTree(out);
    }

    static Set<String> strings(JsonNode array) {
        Set<String> out = new HashSet<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }

    // ---- seed ----

    @Test
    void seedIsFullyRepresentedAfterImport() throws Exception {
        String json = seedJson();
        JsonNode seed = mapper.readTree(json);
        send(post("/api/cv/seed"), json, 201);

        // profile
        JsonNode profile = getJson("/api/profile");
        JsonNode sp = seed.get("profile");
        for (String f : List.of("fullName", "headline", "email", "phone", "phoneInternational", "city", "website",
                "github", "workAuthorisation", "securityVetting")) {
            assertThat(text(profile, f)).as("profile." + f).isEqualTo(text(sp, f));
        }
        assertThat(profile.get("languages")).isEqualTo(sp.get("languages"));

        // entries, facts and their tags
        JsonNode entries = getJson("/api/cv/entries");
        assertThat(entries).hasSize(seed.get("entries").size());
        Set<String> allTags = new HashSet<>(strings(seed.get("tags")));
        int expectedFacts = 0;
        for (JsonNode se : seed.get("entries")) {
            JsonNode e = findEntry(entries, se);
            assertThat(e.get("kind").asText()).isEqualTo(se.get("kind").asText());
            assertThat(text(e, "titleEn")).isEqualTo(text(se, "titleEn"));
            assertThat(text(e, "organisation")).isEqualTo(text(se, "organisation"));
            assertThat(text(e, "location")).isEqualTo(text(se, "location"));
            assertThat(text(e, "note")).isEqualTo(text(se, "note"));
            assertThat(e.get("ongoing").asBoolean()).isEqualTo(se.path("ongoing").asBoolean(false));
            if (se.has("years")) {
                String[] years = se.get("years").asText().split("-");
                assertThat(text(e, "startYm")).isEqualTo(years[0]);
                assertThat(text(e, "endYm")).isEqualTo(years[1]);
            } else {
                assertThat(text(e, "startYm")).isEqualTo(text(se, "start"));
                assertThat(text(e, "endYm")).isEqualTo(text(se, "end"));
            }
            assertThat(strings(e.get("tags"))).isEqualTo(strings(se.path("tags")));
            allTags.addAll(strings(se.path("tags")));

            assertThat(e.get("facts")).hasSameSizeAs(se.get("facts"));
            for (int i = 0; i < se.get("facts").size(); i++) {
                JsonNode sf = se.get("facts").get(i);
                JsonNode f = e.get("facts").get(i);
                assertThat(text(f, "textEn")).isEqualTo(text(sf, "en"));
                assertThat(text(f, "textSv")).isEqualTo(text(sf, "sv"));
                assertThat(text(f, "evidence")).isEqualTo(text(sf, "evidence"));
                assertThat(strings(f.get("tags"))).isEqualTo(strings(sf.path("tags")));
                allTags.addAll(strings(sf.path("tags")));
                expectedFacts++;
            }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM cv_fact").query(Long.class).single()).isEqualTo(expectedFacts);

        // tags, skills, gaps, answers
        Set<String> storedTags = new HashSet<>();
        getJson("/api/cv/tags").forEach(t -> storedTags.add(t.get("name").asText()));
        assertThat(storedTags).isEqualTo(allTags);

        JsonNode skills = getJson("/api/cv/skills");
        assertThat(skills).hasSameSizeAs(seed.get("skills"));
        for (JsonNode ss : seed.get("skills")) {
            JsonNode s = find(skills, "name", ss.get("name").asText());
            assertThat(text(s, "category")).isEqualTo(text(ss, "category"));
        }

        List<String> gaps = new ArrayList<>();
        getJson("/api/cv/gaps").forEach(g -> gaps.add(g.get("text").asText()));
        assertThat(gaps).containsExactlyElementsOf(textList(seed.get("knownGaps")));

        JsonNode answers = getJson("/api/cv/answers");
        assertThat(answers).hasSameSizeAs(seed.get("answersMemory"));
        for (JsonNode sa : seed.get("answersMemory")) {
            JsonNode a = find(answers, "question", sa.get("question").asText());
            assertThat(a.get("answer").asText()).isEqualTo(sa.get("answer").asText());
            assertThat(a.get("questionKey").asText()).isEqualTo(AnswerKeys.normalise(sa.get("question").asText()));
        }
    }

    @Test
    void seedIsRefusedWhenDatabaseIsNotEmpty() throws Exception {
        send(post("/api/cv/seed"), seedJson(), 201);
        send(post("/api/cv/seed"), seedJson(), 409);
        assertThat(getJson("/api/cv/entries")).hasSize(mapper.readTree(seedJson()).get("entries").size());
    }

    @Test
    void seedWithUnknownFieldIsRejectedAndNothingIsWritten() throws Exception {
        ObjectMapper m = mapper;
        var seed = (com.fasterxml.jackson.databind.node.ObjectNode) m.readTree(seedJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) seed.get("entries").get(0)).put("surprise", "x");

        send(post("/api/cv/seed"), seed.toString(), 400);

        assertThat(getJson("/api/cv/entries")).isEmpty();
    }

    @Test
    void seedWithUnreadableYearsFailsAndRollsBack() throws Exception {
        var seed = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(seedJson());
        for (JsonNode e : seed.get("entries")) {
            if (e.has("years")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) e).put("years", "2024 to 2026");
            }
        }
        send(post("/api/cv/seed"), seed.toString(), 400);

        assertThat(getJson("/api/cv/entries")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM profile").query(Long.class).single()).isZero();
    }

    // ---- CRUD ----

    @Test
    void editingAnEntryKeepsFactIds() throws Exception {
        JsonNode entry = send(post("/api/cv/entries"), """
                {"kind":"WORK","title":"Teacher","organisation":"School","tags":["teaching"]}""", 201);
        long entryId = entry.get("id").asLong();
        long f1 = send(post("/api/cv/entries/" + entryId + "/facts"), """
                {"textEn":"Taught PE to 200 pupils","tags":["teaching"]}""", 201).get("id").asLong();
        long f2 = send(post("/api/cv/entries/" + entryId + "/facts"), """
                {"textEn":"Ran a coaching programme","textSv":"Ledde ett coachningsprogram"}""", 201).get("id").asLong();

        JsonNode updated = send(put("/api/cv/entries/" + entryId), """
                {"kind":"WORK","title":"PE teacher","organisation":"School","startYm":"2019-08","endYm":"2021",
                 "tags":["pedagogy","sport-science"],"note":"check dates"}""", 200);

        assertThat(updated.get("title").asText()).isEqualTo("PE teacher");
        assertThat(strings(updated.get("tags"))).containsExactlyInAnyOrder("pedagogy", "sport-science");
        List<Long> factIds = new ArrayList<>();
        updated.get("facts").forEach(f -> factIds.add(f.get("id").asLong()));
        assertThat(factIds).containsExactly(f1, f2);
        assertThat(strings(updated.get("facts").get(0).get("tags"))).containsExactly("teaching");
    }

    @Test
    void factsCanBeEditedAndDeleted() throws Exception {
        long entryId = send(post("/api/cv/entries"), """
                {"kind":"PROJECT","title":"Portfolio"}""", 201).get("id").asLong();
        long factId = send(post("/api/cv/entries/" + entryId + "/facts"), """
                {"textEn":"Built a site","tags":["frontend"]}""", 201).get("id").asLong();

        JsonNode f = send(put("/api/cv/facts/" + factId), """
                {"textEn":"Built a portfolio site","textSv":"Byggde en portfolio","evidence":"caibirch.com",
                 "metrics":"3 pages","tags":["frontend","react"]}""", 200);
        assertThat(f.get("textSv").asText()).isEqualTo("Byggde en portfolio");
        assertThat(strings(f.get("tags"))).containsExactlyInAnyOrder("frontend", "react");

        send(delete("/api/cv/facts/" + factId), "", 204);
        assertThat(getJson("/api/cv/entries/" + entryId).get("facts")).isEmpty();
        send(delete("/api/cv/facts/" + factId), "", 404);
    }

    @Test
    void deletingAnEntryDeletesItsFacts() throws Exception {
        long entryId = send(post("/api/cv/entries"), """
                {"kind":"OTHER","title":"Misc"}""", 201).get("id").asLong();
        send(post("/api/cv/entries/" + entryId + "/facts"), """
                {"textEn":"Something"}""", 201);

        send(delete("/api/cv/entries/" + entryId), "", 204);

        assertThat(jdbc.sql("SELECT count(*) FROM cv_fact").query(Long.class).single()).isZero();
    }

    @Test
    void invalidInputGivesClearErrors() throws Exception {
        mvc.perform(post("/api/cv/entries").contentType(MediaType.APPLICATION_JSON).content("""
                {"kind":"WORK","title":"X","startYm":"2024-13"}""")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("startYm must be YYYY-MM or YYYY"));
        mvc.perform(post("/api/cv/entries").contentType(MediaType.APPLICATION_JSON).content("""
                {"kind":"WORK","title":" "}""")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/cv/entries").contentType(MediaType.APPLICATION_JSON).content("""
                {"kind":"NOPE","title":"X"}""")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/cv/entries/999/facts").contentType(MediaType.APPLICATION_JSON).content("""
                {"textEn":"x"}""")).andExpect(status().isNotFound());
        mvc.perform(get("/api/cv/entries/999")).andExpect(status().isNotFound());
    }

    @Test
    void profileStartsEmptyAndCanBeSaved() throws Exception {
        assertThat(getJson("/api/profile").get("id").isNull()).isTrue();

        send(put("/api/profile"), """
                {"fullName":"Cai Birch","email":"c@example.com","languages":[{"language":"Swedish","level":"fluent"}]}""", 200);
        JsonNode saved = send(put("/api/profile"), """
                {"fullName":"Cai Birch","city":"Stockholm","languages":[]}""", 200);

        assertThat(saved.get("city").asText()).isEqualTo("Stockholm");
        assertThat(jdbc.sql("SELECT count(*) FROM profile").query(Long.class).single()).isEqualTo(1);
        send(put("/api/profile"), """
                {"fullName":""}""", 400);
    }

    @Test
    void skillsAndAnswersAreUpserted() throws Exception {
        send(post("/api/cv/skills"), """
                {"name":"Java","category":"language","years":3.5,"lastUsedYm":"2026-05"}""", 201);
        send(post("/api/cv/skills"), """
                {"name":"Java","category":"language","level":"strong"}""", 201);
        assertThat(getJson("/api/cv/skills")).hasSize(1);
        assertThat(getJson("/api/cv/skills").get(0).get("level").asText()).isEqualTo("strong");

        send(put("/api/cv/answers"), """
                {"question":"Erfarenhet av Figma?","answer":"Nej"}""", 204);
        send(put("/api/cv/answers"), """
                {"question":"erfarenhet av figma","answer":"Nej, men angelägen att lära mig"}""", 204);
        assertThat(getJson("/api/cv/answers")).hasSize(1);
    }

    // ---- helpers ----

    static List<String> textList(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    /** Titles repeat in the seed (two internships), so match on title and organisation. */
    static JsonNode findEntry(JsonNode entries, JsonNode seedEntry) {
        for (JsonNode n : entries) {
            if (seedEntry.get("title").asText().equals(n.path("title").asText())
                    && java.util.Objects.equals(text(seedEntry, "organisation"), text(n, "organisation"))) {
                return n;
            }
        }
        throw new AssertionError("No entry for " + seedEntry.get("key"));
    }

    static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.path(field).asText())) {
                return n;
            }
        }
        throw new AssertionError("No element with " + field + " = " + value);
    }
}
