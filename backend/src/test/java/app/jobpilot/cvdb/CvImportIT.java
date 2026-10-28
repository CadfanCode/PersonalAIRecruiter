package app.jobpilot.cvdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import app.jobpilot.claude.ClaudeRunner;
import app.jobpilot.claude.FakeClaudeRunner;
import app.jobpilot.support.PostgresTestConfig;

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
    void clean() throws Exception {
        jdbc.sql("TRUNCATE cv_entry, profile, tag, skill, known_gap, answers_memory RESTART IDENTITY CASCADE").update();
        claude.calls().clear();
        claude.respond("importCv", CLAUDE_ANSWER);
        claude.respond("matchFacts", "{\"results\":[]}");
        // readings left over from other tests would be found by /current
        for (var r = mvc.perform(get("/api/cv/import/current")).andReturn().getResponse(); r.getStatus() == 200;
                r = mvc.perform(get("/api/cv/import/current")).andReturn().getResponse()) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/cv/import/"
                    + mapper.readTree(r.getContentAsString()).get("runId").asText()));
        }
    }

    MockMultipartFile txt(String name, String content) {
        return new MockMultipartFile("files", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    /** Uploads, then waits for the background reading to finish and returns its preview. */
    JsonNode preview(MockMultipartFile... files) throws Exception {
        var req = multipart("/api/cv/import");
        for (MockMultipartFile f : files) {
            req.file(f);
        }
        JsonNode started = body(mvc.perform(req).andExpect(status().isOk()));
        assertThat(started.get("status").asText()).isIn("RUNNING", "DONE");
        for (int i = 0; i < 100; i++) {
            JsonNode job = body(mvc.perform(get("/api/cv/import/" + started.get("runId").asText())).andExpect(status().isOk()));
            if (!job.get("status").asText().equals("RUNNING")) {
                assertThat(job.get("status").asText()).as(job.toString()).isEqualTo("DONE");
                return job.get("preview");
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the reading did not finish");
    }

    JsonNode body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return mapper.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** One confirmed entry: new when {@code mergeInto} is null, else added to that stored entry. */
    JsonNode confirm(JsonNode entry, Long mergeInto) {
        return confirm(entry, mergeInto, null);
    }

    JsonNode confirm(JsonNode entry, Long mergeInto, JsonNode skipFacts) {
        var node = mapper.createObjectNode();
        node.set("entry", entry);
        if (skipFacts != null) {
            node.set("skipFacts", skipFacts);
        }
        if (mergeInto == null) {
            node.putNull("mergeIntoEntryId");
        } else {
            node.put("mergeIntoEntryId", mergeInto);
        }
        return node;
    }

    JsonNode commit(JsonNode preview, java.util.List<JsonNode> entries) throws Exception {
        var request = mapper.createObjectNode();
        var array = request.putArray("entries");
        entries.forEach(array::add);
        request.set("skills", preview.get("skills"));
        request.set("profile", preview.get("profile"));
        return body(mvc.perform(post("/api/cv/import/" + preview.get("previewId").asText() + "/commit")
                .contentType(MediaType.APPLICATION_JSON).content(request.toString())).andExpect(status().isOk()));
    }

    /** Confirms every entry the way the wizard does by default: matched ones are merged, the rest are new. */
    JsonNode commitAll(JsonNode preview) throws Exception {
        java.util.List<JsonNode> all = new java.util.ArrayList<>();
        for (JsonNode e : preview.get("entries")) {
            all.add(confirm(e.get("entry"), e.get("matchEntryId").isNull() ? null : e.get("matchEntryId").asLong(), e.get("knownFacts")));
        }
        return commit(preview, all);
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
    void entriesAlreadyInTheDatabaseAreMatchedAndSayHowManyFactsTheyAdd() throws Exception {
        mvc.perform(post("/api/cv/entries").contentType(MediaType.APPLICATION_JSON).content("""
                {"kind":"INTERNSHIP","title":"Systemutvecklare (praktik)","organisation":"Braive AB"}"""))
                .andExpect(status().isCreated());

        JsonNode preview = preview(txt("cv.txt", "x"));

        JsonNode first = preview.get("entries").get(0);
        assertThat(first.get("matchEntryId").isNull()).isFalse();
        assertThat(first.get("matchTitle").asText()).isEqualTo("Systemutvecklare (praktik), Braive AB");
        assertThat(first.get("newFacts").asInt()).isEqualTo(1);
        assertThat(preview.get("entries").get(1).get("matchEntryId").isNull()).isTrue();
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
        entries.add(confirm(teacher, null));
        request.set("skills", preview.get("skills"));

        mvc.perform(post("/api/cv/import/" + id + "/commit").contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryIds.length()").value(1))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.enriched").value(0))
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
        entries.add(confirm(preview.get("entries").get(0).get("entry"), null));
        var bad = (com.fasterxml.jackson.databind.node.ObjectNode) preview.get("entries").get(1).get("entry");
        bad.put("startYm", "soon");
        entries.add(confirm(bad, null));
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

    @Test
    void readingTheSameCvTwiceAddsNothingTheSecondTime() throws Exception {
        JsonNode first = commitAll(preview(txt("cv.txt", "x")));
        assertThat(first.get("created").asInt()).isEqualTo(2);
        assertThat(first.get("facts").asInt()).isEqualTo(3);

        JsonNode second = commitAll(preview(txt("cv.txt", "x")));

        assertThat(second.get("created").asInt()).as("no new entries").isZero();
        assertThat(second.get("enriched").asInt()).isEqualTo(2);
        assertThat(second.get("facts").asInt()).as("no duplicate facts").isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM cv_entry").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM cv_fact").query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void aSecondCvWithAnExtraBulletAddsThatFactToTheSameEntryAndLeavesTheOldOnesAlone() throws Exception {
        commitAll(preview(txt("cv.txt", "x")));
        long factId = jdbc.sql("SELECT id FROM cv_fact WHERE text_en = 'Taught PE.'").query(Long.class).single();
        claude.respond("importCv", CLAUDE_ANSWER.replace("{\"textEn\":\"Coached a team.\"",
                "{\"textEn\":\"Led the school's sports day for 400 pupils.\",\"textSv\":null,\"tags\":[]},{\"textEn\":\"Coached a team.\""));

        JsonNode result = commitAll(preview(txt("cv2.txt", "x")));

        assertThat(result.get("facts").asInt()).isEqualTo(1);
        assertThat(result.get("created").asInt()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM cv_entry WHERE title = 'Idrottslärare'").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT text_en FROM cv_fact WHERE text_en LIKE 'Led the school%'").query(String.class).single()).isNotBlank();
        assertThat(jdbc.sql("SELECT text_en FROM cv_fact WHERE id = :id").param("id", factId).query(String.class).single())
                .as("existing facts keep their id and text").isEqualTo("Taught PE.");
    }

    @Test
    void theSameEmployerWithADifferentRoleAndDatesIsANewEntry() throws Exception {
        mvc.perform(post("/api/cv/entries").contentType(MediaType.APPLICATION_JSON).content("""
                {"kind":"WORK","title":"Undersköterska","organisation":"Akademiska sjukhuset","startYm":"2015-01","endYm":"2018-12"}"""))
                .andExpect(status().isCreated());
        claude.respond("importCv", """
                {"entries":[{"kind":"WORK","title":"Sjuksköterska","titleEn":null,"organisation":"Akademiska sjukhuset","location":null,
                  "startYm":"2019-01","endYm":null,"ongoing":true,"tags":[],"facts":[{"textEn":"Triage.","textSv":null,"tags":[]}]}],
                 "skills":[],"profile":null}""");

        JsonNode preview = preview(txt("cv.txt", "x"));

        assertThat(preview.get("entries").get(0).get("matchEntryId").isNull()).as("a promotion is not the same job").isTrue();
        assertThat(commitAll(preview).get("created").asInt()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM cv_entry").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void mergingFillsBlankFieldsAndAddsTagsButNeverOverwritesWhatIsThere() throws Exception {
        mvc.perform(post("/api/cv/entries").contentType(MediaType.APPLICATION_JSON).content("""
                {"kind":"WORK","title":"Idrottslärare (mine)","organisation":"Nacka skola","startYm":"2019","location":"Nacka","tags":["mine"]}"""))
                .andExpect(status().isCreated());
        claude.respond("importCv", CLAUDE_ANSWER.replace("\"location\":null,\n               \"startYm\":\"2019\"", "\"location\":\"Stockholm\",\n               \"startYm\":\"2019\""));

        commitAll(preview(txt("cv.txt", "x")));

        assertThat(jdbc.sql("SELECT title FROM cv_entry WHERE organisation = 'Nacka skola'").query(String.class).single()).isEqualTo("Idrottslärare (mine)");
        assertThat(jdbc.sql("SELECT location FROM cv_entry WHERE organisation = 'Nacka skola'").query(String.class).single()).as("the user's value stays").isEqualTo("Nacka");
        assertThat(jdbc.sql("SELECT count(*) FROM entry_tag et JOIN tag t ON t.id = et.tag_id JOIN cv_entry e ON e.id = et.entry_id WHERE e.organisation = 'Nacka skola'")
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void twoFilesDescribingTheSameJobGiveOneEntryWithBothFilesFacts() throws Exception {
        claude.respond("importCv", """
                {"entries":[
                  {"kind":"WORK","title":"Sjuksköterska","titleEn":null,"organisation":"Capio","location":null,"startYm":"2020","endYm":null,"ongoing":true,"tags":[],
                   "facts":[{"textEn":"Triage on the emergency ward.","textSv":null,"tags":[]}]},
                  {"kind":"WORK","title":"Legitimerad sjuksköterska","titleEn":null,"organisation":"Capio AB","location":"Uppsala","startYm":"2020-03","endYm":null,"ongoing":true,"tags":[],
                   "facts":[{"textEn":"Triage on the emergency ward","textSv":null,"tags":[]},{"textEn":"Mentored students.","textSv":null,"tags":[]}]}],
                 "skills":[],"profile":null}""");

        JsonNode preview = preview(txt("cv.txt", "x"), txt("cv-2.txt", "y"));

        assertThat(preview.get("entries")).hasSize(1);
        assertThat(preview.get("entries").get(0).get("entry").get("facts")).as("the repeated fact is kept once").hasSize(2);
        assertThat(preview.get("entries").get(0).get("entry").get("location").asText()).isEqualTo("Uppsala");
    }

    @Test
    void anEntryCanBeAddedAsNewEvenWhenItMatches() throws Exception {
        commitAll(preview(txt("cv.txt", "x")));
        JsonNode again = preview(txt("cv.txt", "x"));
        java.util.List<JsonNode> asNew = new java.util.ArrayList<>();
        for (JsonNode e : again.get("entries")) {
            asNew.add(confirm(e.get("entry"), null));
        }

        JsonNode result = commit(again, asNew);

        assertThat(result.get("created").asInt()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM cv_entry").query(Long.class).single()).isEqualTo(4);
    }

    @Test
    void theNewestReadingCanBeFoundAgainUntilItIsConfirmedOrDiscarded() throws Exception {
        mvc.perform(get("/api/cv/import/current")).andExpect(status().isNoContent());
        JsonNode preview = preview(txt("cv.txt", "x"));

        JsonNode current = body(mvc.perform(get("/api/cv/import/current")).andExpect(status().isOk()));
        assertThat(current.get("status").asText()).isEqualTo("DONE");
        assertThat(current.get("preview").get("previewId").asText()).isEqualTo(preview.get("previewId").asText());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/cv/import/" + current.get("runId").asText())).andExpect(status().isOk());
        mvc.perform(get("/api/cv/import/current")).andExpect(status().isNoContent());
    }

    @Test
    void aFailedReadingIsReportedNotThrown() throws Exception {
        claude.respond("importCv", t -> {
            throw new app.jobpilot.claude.ClaudeCallException("Claude took too long");
        });
        JsonNode started = body(mvc.perform(multipart("/api/cv/import").file(txt("cv.txt", "x"))).andExpect(status().isOk()));

        JsonNode job = null;
        for (int i = 0; i < 100 && (job == null || job.get("status").asText().equals("RUNNING")); i++) {
            job = body(mvc.perform(get("/api/cv/import/" + started.get("runId").asText())).andExpect(status().isOk()));
            Thread.sleep(50);
        }
        assertThat(job.get("status").asText()).isEqualTo("FAILED");
        assertThat(job.get("message").asText()).contains("took too long");
    }

    /** Claude's answer to "which of these candidate facts are already stated": every candidate that is listed. */
    private static String everyCandidateIsKnown(app.jobpilot.claude.ClaudeTask<?> task) {
        var entry = java.util.regex.Pattern.compile("\"entryId\"\\s*:\\s*(\\d+)").matcher(task.prompt());
        var idx = java.util.regex.Pattern.compile("\"idx\"\\s*:\\s*(\\d+)").matcher(task.prompt());
        entry.find();
        java.util.List<String> all = new java.util.ArrayList<>();
        while (idx.find()) {
            all.add(idx.group(1));
        }
        return "{\"results\":[{\"entryId\":" + entry.group(1) + ",\"alreadyStated\":[" + String.join(",", all) + "]}]}";
    }

    @Test
    void aRewordedBulletIsRecognisedByClaudeAsAlreadyThereAndNotAddedAgain() throws Exception {
        commitAll(preview(txt("cv.txt", "x")));
        claude.respond("importCv", CLAUDE_ANSWER.replace("Taught PE.", "Delivered gymnastics lessons for middle school pupils.").replace("Undervisade i idrott.", "Höll gympalektioner för mellanstadieelever."));
        claude.respond("matchFacts", CvImportIT::everyCandidateIsKnown);

        JsonNode second = preview(txt("cv2.txt", "x"));

        JsonNode teacher = second.get("entries").get(1);
        assertThat(teacher.get("newFacts").asInt()).as("the reworded bullet is not new").isZero();
        assertThat(teacher.get("knownFacts")).hasSize(2);
        assertThat(claude.calls().stream().filter(c -> c.name().equals("matchFacts")).toList()).as("one batched call").hasSize(1);
        assertThat(commitAll(second).get("facts").asInt()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM cv_fact").query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void aSwedishOnlyStoredFactAndItsEnglishOnlyTwinAreTheSameFact() throws Exception {
        commitAll(preview(txt("cv.txt", "x")));
        jdbc.sql("UPDATE cv_fact SET text_sv = 'Undervisade i idrott.', text_en = NULL WHERE text_en = 'Taught PE.'").update();
        claude.respond("importCv", CLAUDE_ANSWER.replace("\"textSv\":\"Undervisade i idrott.\"", "\"textSv\":null"));
        claude.respond("matchFacts", CvImportIT::everyCandidateIsKnown);

        JsonNode result = commitAll(preview(txt("cv2.txt", "x")));

        assertThat(result.get("facts").asInt()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM cv_fact").query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void whatTheReviewDecidedIsWhatIsCommittedEvenIfTheWordMatcherWouldDisagree() throws Exception {
        commitAll(preview(txt("cv.txt", "x")));
        JsonNode second = preview(txt("cv2.txt", "x"));
        var teacher = second.get("entries").get(1);
        assertThat(teacher.get("knownFacts")).as("the word matcher knows both").hasSize(2);

        // the review says the second fact is new after all
        JsonNode result = commit(second, java.util.List.of(confirm(teacher.get("entry"), teacher.get("matchEntryId").asLong(), mapper.createArrayNode().add(0))));

        assertThat(result.get("facts").asInt()).isEqualTo(1);
    }

    @Test
    void ifTheComparisonCallFailsTheWordMatchStandsAndTheReadingStillSucceeds() throws Exception {
        commitAll(preview(txt("cv.txt", "x")));
        claude.respond("importCv", CLAUDE_ANSWER.replace("Taught PE.", "Delivered gymnastics lessons.").replace("Undervisade i idrott.", "Höll gympalektioner."));
        claude.respond("matchFacts", t -> {
            throw new app.jobpilot.claude.ClaudeCallException("Claude took too long");
        });

        JsonNode second = preview(txt("cv2.txt", "x"));

        assertThat(second.get("entries").get(1).get("newFacts").asInt()).as("treated as new, which the user can untick").isEqualTo(1);
    }
}
