package se.caibirch.jobpilot.cvdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import se.caibirch.jobpilot.claude.ClaudeRunner;
import se.caibirch.jobpilot.claude.FakeClaudeRunner;
import se.caibirch.jobpilot.support.PostgresTestConfig;

@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, CvImportIT.Fakes.class})
class CvImportIT {

    static final String CLAUDE_ANSWER = """
            {"entries":[
              {"kind":"INTERNSHIP","title":"Systemutvecklare (praktik)","titleEn":"Software Developer (intern)",
               "organisation":"Braive AB","location":"Stockholm","startYm":"2026-01","endYm":"2026-05","ongoing":false,
               "tags":["java"],"facts":[{"textEn":"Built microservices.","textSv":"Byggde mikrotjänster.","tags":["java"]}]},
              {"kind":"WORK","title":"Idrottslärare","titleEn":null,"organisation":"Nacka skola","location":null,
               "startYm":"2019","endYm":null,"ongoing":true,"tags":["teaching"],
               "facts":[{"textEn":"Taught PE.","textSv":"Undervisade i idrott.","evidence":"letter","tags":[]},
                        {"textEn":"Coached a team.","textSv":null,"tags":["coaching"]}]}],
             "skills":[{"name":"Java","category":"language"},{"name":"Spring Boot"}],
             "profile":{"fullName":"Alex Lindgren","headline":"Nurse","email":"alex@example.com","phone":null,"city":"Uppsala",
                        "website":null,"linkedin":"https://www.linkedin.com/in/alex","github":null}}""";

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeClaudeRunner fakeRunner() {
            return new FakeClaudeRunner().respond("importCv", CLAUDE_ANSWER);
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

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE cv_entry, profile, tag, skill, known_gap, answers_memory RESTART IDENTITY CASCADE").update();
        claude.calls().clear();
    }

    MockMultipartFile txt(String name, String content) {
        return new MockMultipartFile("files", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    JsonNode preview(MockMultipartFile... files) throws Exception {
        var req = multipart("/api/cv/import");
        for (MockMultipartFile f : files) {
            req.file(f);
        }
        return mapper.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void previewWritesNothingAndPassesTheTextToClaude() throws Exception {
        jdbc.sql("INSERT INTO tag (name) VALUES ('security')").update();

        JsonNode preview = preview(txt("cv.txt", "Braive AB, praktik 2026"), txt("brev.md", "Jag älskar backend"));

        assertThat(preview.get("entries")).hasSize(2);
        assertThat(preview.get("files").get(1).asText()).isEqualTo("brev.md");
        assertThat(preview.get("skills")).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM cv_entry").query(Long.class).single()).isZero();
        String prompt = claude.calls().get(0).prompt();
        assertThat(prompt).contains("Braive AB, praktik 2026").contains("Jag älskar backend")
                .contains("security").contains("Extract only what the text states");
    }

    @Test
    void entriesAlreadyInTheDatabaseAreFlaggedAsDuplicates() throws Exception {
        mvc.perform(post("/api/cv/entries").contentType(MediaType.APPLICATION_JSON).content("""
                {"kind":"INTERNSHIP","title":"Systemutvecklare (praktik)","organisation":"Braive AB"}"""))
                .andExpect(status().isCreated());

        JsonNode preview = preview(txt("cv.txt", "x"));

        assertThat(preview.get("entries").get(0).get("duplicateOfEntryId").isNull()).isFalse();
        assertThat(preview.get("entries").get(1).get("duplicateOfEntryId").isNull()).isTrue();
    }

    @Test
    void importNeverOverwritesExistingSkillDetails() throws Exception {
        jdbc.sql("INSERT INTO skill (name, category, level, years) VALUES ('Java', 'language', 'strong', 3.5)").update();
        JsonNode preview = preview(txt("cv.txt", "x"));
        var request = mapper.createObjectNode();
        request.putArray("entries");
        request.set("skills", preview.get("skills"));

        mvc.perform(post("/api/cv/import/" + preview.get("previewId").asText() + "/commit")
                .contentType(MediaType.APPLICATION_JSON).content(request.toString())).andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT level FROM skill WHERE name = 'Java'").query(String.class).single()).isEqualTo("strong");
        assertThat(jdbc.sql("SELECT count(*) FROM skill").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void commitFillsOnlyTheBlankProfileFieldsFromTheDocuments() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/profile").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Alex L\",\"city\":\"Stockholm\",\"languages\":[]}")).andExpect(status().isOk());
        JsonNode preview = preview(txt("cv.txt", "x"));
        assertThat(preview.get("profile").get("email").asText()).isEqualTo("alex@example.com");
        var request = mapper.createObjectNode();
        request.putArray("entries");
        request.set("skills", preview.get("skills"));
        request.set("profile", preview.get("profile"));

        mvc.perform(post("/api/cv/import/" + preview.get("previewId").asText() + "/commit")
                .contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profileFilled").value(true));

        assertThat(jdbc.sql("SELECT full_name FROM profile").query(String.class).single()).as("kept what the user entered").isEqualTo("Alex L");
        assertThat(jdbc.sql("SELECT city FROM profile").query(String.class).single()).isEqualTo("Stockholm");
        assertThat(jdbc.sql("SELECT email FROM profile").query(String.class).single()).as("filled a blank").isEqualTo("alex@example.com");
        assertThat(jdbc.sql("SELECT linkedin FROM profile").query(String.class).single()).isEqualTo("https://www.linkedin.com/in/alex");
        assertThat(jdbc.sql("SELECT headline FROM profile").query(String.class).single()).isEqualTo("Nurse");
    }

    @Test
    void commitStoresTheEditedEntriesAndFactsOnce() throws Exception {
        JsonNode preview = preview(txt("cv.txt", "x"));
        String id = preview.get("previewId").asText();
        var request = mapper.createObjectNode();
        var entries = request.putArray("entries");
        JsonNode teacher = preview.get("entries").get(1).get("entry");
        ((com.fasterxml.jackson.databind.node.ObjectNode) teacher).put("title", "Idrottslärare (edited)");
        entries.add(teacher);
        request.set("skills", preview.get("skills"));

        mvc.perform(post("/api/cv/import/" + id + "/commit").contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryIds.length()").value(1))
                .andExpect(jsonPath("$.facts").value(2));

        assertThat(jdbc.sql("SELECT title FROM cv_entry").query(String.class).list()).containsExactly("Idrottslärare (edited)");
        assertThat(jdbc.sql("SELECT count(*) FROM cv_fact").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM skill").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM entry_tag").query(Long.class).single()).isEqualTo(1);

        mvc.perform(post("/api/cv/import/" + id + "/commit").contentType(MediaType.APPLICATION_JSON)
                .content(request.toString())).andExpect(status().isNotFound());
    }

    @Test
    void invalidCommitRollsBackEverything() throws Exception {
        JsonNode preview = preview(txt("cv.txt", "x"));
        var request = mapper.createObjectNode();
        var entries = request.putArray("entries");
        entries.add(preview.get("entries").get(0).get("entry"));
        var bad = (com.fasterxml.jackson.databind.node.ObjectNode) preview.get("entries").get(1).get("entry");
        bad.put("startYm", "soon");
        entries.add(bad);
        request.putArray("skills");

        mvc.perform(post("/api/cv/import/" + preview.get("previewId").asText() + "/commit")
                .contentType(MediaType.APPLICATION_JSON).content(request.toString())).andExpect(status().isBadRequest());

        assertThat(jdbc.sql("SELECT count(*) FROM cv_entry").query(Long.class).single()).isZero();
    }

    @Test
    void unsupportedUploadIsABadRequest() throws Exception {
        mvc.perform(multipart("/api/cv/import").file(new MockMultipartFile("files", "a.png", "image/png", new byte[] {1})))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Unsupported")));
    }

    @Test
    void unknownPreviewIsNotFound() throws Exception {
        mvc.perform(post("/api/cv/import/nope/commit").contentType(MediaType.APPLICATION_JSON)
                .content("{\"entries\":[],\"skills\":[]}")).andExpect(status().isNotFound());
    }
}
