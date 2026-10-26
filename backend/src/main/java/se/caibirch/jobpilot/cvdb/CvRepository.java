package se.caibirch.jobpilot.cvdb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import se.caibirch.jobpilot.cvdb.CvModel.*;

/**
 * JDBC access to the CV database. Entries and facts are written separately on purpose: updating an
 * entry never touches its facts, because later milestones cite {@code cv_fact.id}.
 */
@Repository
public class CvRepository {

    private static final TypeReference<List<Language>> LANGUAGES = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public CvRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ---- profile ----

    public Optional<Profile> findProfile() {
        return jdbc.sql("SELECT * FROM profile ORDER BY id LIMIT 1").query((rs, n) -> new Profile(
                rs.getLong("id"), rs.getString("full_name"), rs.getString("headline"), rs.getString("email"),
                rs.getString("phone"), rs.getString("phone_international"), rs.getString("city"),
                rs.getString("website"), rs.getString("github"), rs.getString("linkedin"),
                rs.getString("work_authorisation"), rs.getString("security_vetting"),
                readLanguages(rs.getString("languages_json")))).optional();
    }

    public Profile saveProfile(Profile p) {
        String languages = writeJson(p.languages() == null ? List.of() : p.languages());
        Optional<Profile> existing = findProfile();
        if (existing.isPresent()) {
            jdbc.sql("""
                    UPDATE profile SET full_name = :fullName, headline = :headline, email = :email, phone = :phone,
                        phone_international = :phoneInternational, city = :city, website = :website,
                        github = :github, linkedin = :linkedin, work_authorisation = :workAuthorisation,
                        security_vetting = :securityVetting, languages_json = :languages::jsonb, updated_at = now()
                    WHERE id = :id
                    """)
                    .params(profileParams(p, languages)).param("id", existing.get().id()).update();
        } else {
            jdbc.sql("""
                    INSERT INTO profile (full_name, headline, email, phone, phone_international, city, website,
                        github, linkedin, work_authorisation, security_vetting, languages_json)
                    VALUES (:fullName, :headline, :email, :phone, :phoneInternational, :city, :website,
                        :github, :linkedin, :workAuthorisation, :securityVetting, :languages::jsonb)
                    """)
                    .params(profileParams(p, languages)).update();
        }
        return findProfile().orElseThrow();
    }

    private static Map<String, Object> profileParams(Profile p, String languagesJson) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fullName", p.fullName());
        m.put("headline", p.headline());
        m.put("email", p.email());
        m.put("phone", p.phone());
        m.put("phoneInternational", p.phoneInternational());
        m.put("city", p.city());
        m.put("website", p.website());
        m.put("github", p.github());
        m.put("linkedin", p.linkedin());
        m.put("workAuthorisation", p.workAuthorisation());
        m.put("securityVetting", p.securityVetting());
        m.put("languages", languagesJson);
        return m;
    }

    // ---- entries ----

    public List<Entry> findAllEntries() {
        Map<Long, List<String>> entryTags = tagNames("SELECT et.entry_id AS owner, t.name FROM entry_tag et "
                + "JOIN tag t ON t.id = et.tag_id ORDER BY t.name");
        Map<Long, List<String>> factTags = tagNames("SELECT ft.fact_id AS owner, t.name FROM fact_tag ft "
                + "JOIN tag t ON t.id = ft.tag_id ORDER BY t.name");
        Map<Long, List<Fact>> facts = new LinkedHashMap<>();
        for (Fact f : jdbc.sql("SELECT * FROM cv_fact ORDER BY entry_id, sort_order, id")
                .query((rs, n) -> new Fact(rs.getLong("id"), rs.getLong("entry_id"), rs.getString("text_en"),
                        rs.getString("text_sv"), rs.getString("evidence"), rs.getString("metrics"),
                        rs.getInt("sort_order"), List.of())).list()) {
            facts.computeIfAbsent(f.entryId(), k -> new ArrayList<>())
                    .add(withTags(f, factTags.getOrDefault(f.id(), List.of())));
        }
        return jdbc.sql("SELECT * FROM cv_entry ORDER BY sort_order, id").query((rs, n) -> new Entry(
                rs.getLong("id"), Kind.valueOf(rs.getString("kind")), rs.getString("title"),
                rs.getString("title_en"), rs.getString("organisation"), rs.getString("location"),
                rs.getString("start_ym"), rs.getString("end_ym"), rs.getBoolean("ongoing"),
                rs.getString("summary"), rs.getString("note"), rs.getInt("sort_order"), rs.getBoolean("hidden"),
                entryTags.getOrDefault(rs.getLong("id"), List.of()),
                facts.getOrDefault(rs.getLong("id"), List.of()))).list();
    }

    public Optional<Entry> findEntry(long id) {
        return findAllEntries().stream().filter(e -> e.id() == id).findFirst();
    }

    public long insertEntry(EntryInput in) {
        long id = jdbc.sql("""
                INSERT INTO cv_entry (kind, title, title_en, organisation, location, start_ym, end_ym, ongoing,
                                      summary, note, sort_order, hidden)
                VALUES (:kind, :title, :titleEn, :organisation, :location, :startYm, :endYm, :ongoing,
                        :summary, :note, :sortOrder, :hidden)
                RETURNING id
                """)
                .params(entryParams(in)).query(Long.class).single();
        setEntryTags(id, in.tags());
        return id;
    }

    /** Updates the entry's own columns and its tags. Facts are left alone. */
    public boolean updateEntry(long id, EntryInput in) {
        int rows = jdbc.sql("""
                UPDATE cv_entry SET kind = :kind, title = :title, title_en = :titleEn, organisation = :organisation,
                    location = :location, start_ym = :startYm, end_ym = :endYm, ongoing = :ongoing,
                    summary = :summary, note = :note, sort_order = :sortOrder, hidden = :hidden, updated_at = now()
                WHERE id = :id
                """).params(entryParams(in)).param("id", id).update();
        if (rows == 0) {
            return false;
        }
        setEntryTags(id, in.tags());
        return true;
    }

    public boolean deleteEntry(long id) {
        return jdbc.sql("DELETE FROM cv_entry WHERE id = :id").param("id", id).update() > 0;
    }

    private static Map<String, Object> entryParams(EntryInput in) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", in.kind().name());
        m.put("title", in.title());
        m.put("titleEn", in.titleEn());
        m.put("organisation", in.organisation());
        m.put("location", in.location());
        m.put("startYm", in.startYm());
        m.put("endYm", in.endYm());
        m.put("ongoing", Boolean.TRUE.equals(in.ongoing()));
        m.put("summary", in.summary());
        m.put("note", in.note());
        m.put("sortOrder", in.sortOrder() == null ? 0 : in.sortOrder());
        m.put("hidden", Boolean.TRUE.equals(in.hidden()));
        return m;
    }

    private void setEntryTags(long entryId, List<String> names) {
        jdbc.sql("DELETE FROM entry_tag WHERE entry_id = :id").param("id", entryId).update();
        for (String name : distinct(names)) {
            jdbc.sql("INSERT INTO entry_tag (entry_id, tag_id) VALUES (:e, :t)")
                    .param("e", entryId).param("t", upsertTag(name)).update();
        }
    }

    // ---- facts ----

    public Optional<Fact> findFact(long id) {
        return findAllEntries().stream().flatMap(e -> e.facts().stream()).filter(f -> f.id() == id).findFirst();
    }

    public long insertFact(long entryId, FactInput in) {
        long id = jdbc.sql("""
                INSERT INTO cv_fact (entry_id, text_en, text_sv, evidence, metrics, sort_order)
                VALUES (:entryId, :textEn, :textSv, :evidence, :metrics, :sortOrder) RETURNING id
                """).params(factParams(in)).param("entryId", entryId).query(Long.class).single();
        setFactTags(id, in.tags());
        return id;
    }

    public boolean updateFact(long id, FactInput in) {
        int rows = jdbc.sql("""
                UPDATE cv_fact SET text_en = :textEn, text_sv = :textSv, evidence = :evidence, metrics = :metrics,
                    sort_order = :sortOrder WHERE id = :id
                """).params(factParams(in)).param("id", id).update();
        if (rows == 0) {
            return false;
        }
        setFactTags(id, in.tags());
        return true;
    }

    public boolean deleteFact(long id) {
        return jdbc.sql("DELETE FROM cv_fact WHERE id = :id").param("id", id).update() > 0;
    }

    public boolean entryExists(long id) {
        return jdbc.sql("SELECT count(*) FROM cv_entry WHERE id = :id").param("id", id).query(Long.class).single() > 0;
    }

    private static Map<String, Object> factParams(FactInput in) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("textEn", in.textEn());
        m.put("textSv", in.textSv());
        m.put("evidence", in.evidence());
        m.put("metrics", in.metrics());
        m.put("sortOrder", in.sortOrder() == null ? 0 : in.sortOrder());
        return m;
    }

    private void setFactTags(long factId, List<String> names) {
        jdbc.sql("DELETE FROM fact_tag WHERE fact_id = :id").param("id", factId).update();
        for (String name : distinct(names)) {
            jdbc.sql("INSERT INTO fact_tag (fact_id, tag_id) VALUES (:f, :t)")
                    .param("f", factId).param("t", upsertTag(name)).update();
        }
    }

    // ---- tags, skills ----

    public long upsertTag(String name) {
        return jdbc.sql("INSERT INTO tag (name) VALUES (:n) ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name RETURNING id")
                .param("n", name).query(Long.class).single();
    }

    public List<Tag> findAllTags() {
        return jdbc.sql("SELECT id, name FROM tag ORDER BY name")
                .query((rs, n) -> new Tag(rs.getLong("id"), rs.getString("name"))).list();
    }

    public List<Skill> findAllSkills() {
        return jdbc.sql("SELECT * FROM skill ORDER BY category NULLS LAST, name").query((rs, n) -> new Skill(
                rs.getLong("id"), rs.getString("name"), rs.getString("category"), rs.getString("level"),
                rs.getBigDecimal("years"), rs.getString("last_used_ym"))).list();
    }

    public Skill upsertSkill(SkillInput in) {
        return jdbc.sql("""
                INSERT INTO skill (name, category, level, years, last_used_ym)
                VALUES (:name, :category, :level, :years, :lastUsedYm)
                ON CONFLICT (name) DO UPDATE SET category = EXCLUDED.category, level = EXCLUDED.level,
                    years = EXCLUDED.years, last_used_ym = EXCLUDED.last_used_ym
                RETURNING id, name, category, level, years, last_used_ym
                """)
                .param("name", in.name()).param("category", in.category()).param("level", in.level())
                .param("years", in.years()).param("lastUsedYm", in.lastUsedYm())
                .query((rs, n) -> new Skill(rs.getLong("id"), rs.getString("name"), rs.getString("category"),
                        rs.getString("level"), rs.getBigDecimal("years"), rs.getString("last_used_ym")))
                .single();
    }

    public boolean deleteSkill(long id) {
        return jdbc.sql("DELETE FROM skill WHERE id = :id").param("id", id).update() > 0;
    }

    // ---- known gaps, answers ----

    public List<KnownGap> findGaps() {
        return jdbc.sql("SELECT * FROM known_gap ORDER BY sort_order, id")
                .query((rs, n) -> new KnownGap(rs.getLong("id"), rs.getString("text"), rs.getInt("sort_order"))).list();
    }

    public void replaceGaps(List<String> texts) {
        jdbc.sql("DELETE FROM known_gap").update();
        int order = 0;
        for (String text : texts) {
            jdbc.sql("INSERT INTO known_gap (text, sort_order) VALUES (:t, :o)").param("t", text).param("o", order++).update();
        }
    }

    public List<Answer> findAnswers() {
        return jdbc.sql("SELECT * FROM answers_memory ORDER BY question")
                .query((rs, n) -> new Answer(rs.getLong("id"), rs.getString("question_key"),
                        rs.getString("question"), rs.getString("answer"))).list();
    }

    public void upsertAnswer(String question, String answer) {
        jdbc.sql("""
                INSERT INTO answers_memory (question_key, question, answer) VALUES (:k, :q, :a)
                ON CONFLICT (question_key) DO UPDATE SET question = EXCLUDED.question, answer = EXCLUDED.answer,
                    updated_at = now()
                """).param("k", AnswerKeys.normalise(question)).param("q", question).param("a", answer).update();
    }

    // ---- helpers ----

    public boolean isEmpty() {
        return jdbc.sql("SELECT (SELECT count(*) FROM cv_entry) + (SELECT count(*) FROM profile)")
                .query(Long.class).single() == 0;
    }

    private Map<Long, List<String>> tagNames(String sql) {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        jdbc.sql(sql).query((rs, n) -> {
            result.computeIfAbsent(rs.getLong("owner"), k -> new ArrayList<>()).add(rs.getString("name"));
            return null;
        }).list();
        return result;
    }

    private static Fact withTags(Fact f, List<String> tags) {
        return new Fact(f.id(), f.entryId(), f.textEn(), f.textSv(), f.evidence(), f.metrics(), f.sortOrder(), tags);
    }

    private static List<String> distinct(List<String> names) {
        return names == null ? List.of() : names.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    private List<Language> readLanguages(String json) {
        try {
            return mapper.readValue(json, LANGUAGES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt languages_json", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
