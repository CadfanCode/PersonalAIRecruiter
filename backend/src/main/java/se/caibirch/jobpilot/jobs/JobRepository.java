package se.caibirch.jobpilot.jobs;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import se.caibirch.jobpilot.jobs.JobModel.*;

@Repository
public class JobRepository {

    public record Upserted(long id, boolean inserted) {
    }

    private static final String SELECT_ROW = """
            SELECT j.*, s.total, s.tech, s.experience AS score_experience, s.eligibility, s.deal_breakers_json,
                   s.fit_text, s.gaps_text, s.model, s.scored_at
            FROM job j
            LEFT JOIN job_score s ON s.job_id = j.id AND s.search_profile_id = :profile
                 AND s.scored_at = (SELECT max(scored_at) FROM job_score WHERE job_id = j.id AND search_profile_id = :profile)
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JobRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Inserts the ad, or on a dedupe_key collision replaces its source fields only when the new
     * description is longer. id, status, notes and created_at are never touched.
     */
    public Upserted upsert(NewJob j, Long runId) {
        String sql = """
                INSERT INTO job (run_id, source, source_id, url, apply_url, title, employer, client_employer, location,
                    municipality, published_at, deadline, language, employment_type, description_text, raw_json,
                    date_unverified, evidence_url, dedupe_key)
                VALUES (:runId, :source, :sourceId, :url, :applyUrl, :title, :employer, :clientEmployer, :location,
                    :municipality, :publishedAt, :deadline, :language, :employmentType, :description, :raw::jsonb,
                    :dateUnverified, :evidenceUrl, :dedupeKey)
                ON CONFLICT (dedupe_key) DO UPDATE SET source = EXCLUDED.source, source_id = EXCLUDED.source_id,
                    url = EXCLUDED.url, apply_url = EXCLUDED.apply_url, title = EXCLUDED.title,
                    client_employer = EXCLUDED.client_employer, location = EXCLUDED.location,
                    published_at = EXCLUDED.published_at, deadline = EXCLUDED.deadline, language = EXCLUDED.language,
                    employment_type = EXCLUDED.employment_type, description_text = EXCLUDED.description_text,
                    raw_json = EXCLUDED.raw_json, date_unverified = EXCLUDED.date_unverified,
                    evidence_url = EXCLUDED.evidence_url
                WHERE length(coalesce(EXCLUDED.description_text, '')) > length(coalesce(job.description_text, ''))
                RETURNING id, (xmax = 0) AS inserted
                """;
        var spec = jdbc.sql(sql)
                .param("runId", runId).param("source", j.source()).param("sourceId", j.sourceId())
                .param("url", j.url()).param("applyUrl", j.applyUrl()).param("title", j.title())
                .param("employer", j.employer()).param("clientEmployer", j.clientEmployer())
                .param("location", j.location()).param("municipality", j.municipality())
                .param("publishedAt", ts(j.publishedAt())).param("deadline", ts(j.deadline()))
                .param("language", j.language()).param("employmentType", j.employmentType())
                .param("description", j.descriptionText()).param("raw", j.rawJson() == null ? "{}" : j.rawJson())
                .param("dateUnverified", j.dateUnverified()).param("evidenceUrl", j.evidenceUrl())
                .param("dedupeKey", j.dedupeKey());
        Optional<Upserted> row = spec.query((rs, n) -> new Upserted(rs.getLong("id"), rs.getBoolean("inserted")))
                .optional();
        return row.orElseGet(() -> new Upserted(
                jdbc.sql("SELECT id FROM job WHERE dedupe_key = :k").param("k", j.dedupeKey()).query(Long.class).single(),
                false));
    }

    public void linkToRun(long runId, long jobId, double relevance) {
        jdbc.sql("""
                INSERT INTO run_job (run_id, job_id, relevance) VALUES (:r, :j, :rel)
                ON CONFLICT (run_id, job_id) DO UPDATE SET relevance = greatest(run_job.relevance, EXCLUDED.relevance)
                """).param("r", runId).param("j", jobId).param("rel", relevance).update();
    }

    public List<Long> jobIdsOfRun(long runId) {
        return jdbc.sql("SELECT job_id FROM run_job WHERE run_id = :r ORDER BY relevance DESC NULLS LAST, job_id")
                .param("r", runId).query(Long.class).list();
    }

    /** Rows of a run with the latest score for the run's current search profile, ranked. */
    public List<Row> rowsOfRun(long runId, long searchProfileId) {
        List<Row> rows = new ArrayList<>(jdbc.sql(SELECT_ROW
                + " JOIN run_job rj ON rj.job_id = j.id AND rj.run_id = :run")
                .param("profile", searchProfileId).param("run", runId).query(this::row).list());
        rows.sort(RANKING);
        List<Row> ranked = new ArrayList<>();
        int rank = 1;
        for (Row r : rows) {
            ranked.add(r.score() == null ? r : r.withRank(rank++));
        }
        return ranked;
    }

    /** Total desc, then deadline asc (no deadline last), then newest first; unscored ads come last. */
    static final Comparator<Row> RANKING = Comparator
            .comparing((Row r) -> r.score() == null ? Integer.MIN_VALUE : r.score().total(), Comparator.reverseOrder())
            .thenComparing(Row::deadline, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Row::publishedAt, Comparator.nullsLast(Comparator.reverseOrder()));

    public Optional<Row> findRow(long id, Long searchProfileId) {
        return jdbc.sql(SELECT_ROW + " WHERE j.id = :id").param("profile", searchProfileId == null ? -1L : searchProfileId)
                .param("id", id).query(this::row).optional();
    }

    public Optional<String> description(long id) {
        return jdbc.sql("SELECT description_text FROM job WHERE id = :id").param("id", id).query(String.class).optional();
    }

    public Optional<String> rawJson(long id) {
        return jdbc.sql("SELECT raw_json::text FROM job WHERE id = :id").param("id", id).query(String.class).optional();
    }

    public record AdText(long id, String title, String employer, String municipality, String language,
                         Instant publishedAt, Instant deadline, String description, String rawJson) {
    }

    public Optional<AdText> adText(long id) {
        return jdbc.sql("SELECT id, title, employer, municipality, language, published_at, deadline, description_text, "
                + "raw_json::text AS raw FROM job WHERE id = :id").param("id", id)
                .query((rs, n) -> new AdText(rs.getLong("id"), rs.getString("title"), rs.getString("employer"),
                        rs.getString("municipality"), rs.getString("language"), instant(rs, "published_at"),
                        instant(rs, "deadline"), rs.getString("description_text"), rs.getString("raw"))).optional();
    }

    public boolean patch(long id, Status status, String notes) {
        return jdbc.sql("UPDATE job SET status = coalesce(:status, status), notes = coalesce(:notes, notes) WHERE id = :id")
                .param("status", status == null ? null : status.name()).param("notes", notes).param("id", id)
                .update() > 0;
    }

    public void applyScoreFindings(long id, boolean asksForNewGraduates, String experienceRequiredText, String language) {
        jdbc.sql("UPDATE job SET asks_for_new_graduates = :g, experience_required_text = :e, language = :l WHERE id = :id")
                .param("g", asksForNewGraduates).param("e", experienceRequiredText).param("l", language)
                .param("id", id).update();
    }

    // ---- document selection ----

    public record SelectionState(Set<Long> pinned, Set<Long> excluded, String selectionJson) {
    }

    public Optional<SelectionState> selectionState(long jobId) {
        return jdbc.sql("SELECT pinned_entry_ids_json::text AS p, excluded_entry_ids_json::text AS x, selection_json::text AS s "
                + "FROM job WHERE id = :id").param("id", jobId)
                .query((rs, n) -> new SelectionState(ids(rs.getString("p")), ids(rs.getString("x")), rs.getString("s"))).optional();
    }

    public void savePins(long jobId, Set<Long> pinned, Set<Long> excluded) {
        try {
            jdbc.sql("UPDATE job SET pinned_entry_ids_json = :p::jsonb, excluded_entry_ids_json = :x::jsonb WHERE id = :id")
                    .param("p", mapper.writeValueAsString(pinned)).param("x", mapper.writeValueAsString(excluded))
                    .param("id", jobId).update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public void saveSelection(long jobId, String json) {
        jdbc.sql("UPDATE job SET selection_json = :s::jsonb WHERE id = :id").param("s", json).param("id", jobId).update();
    }

    /** The search profile of the newest search run that found this ad. */
    public Optional<Long> latestSearchProfileId(long jobId) {
        return jdbc.sql("""
                SELECT r.search_profile_id FROM run_job rj JOIN pipeline_run r ON r.id = rj.run_id
                WHERE rj.job_id = :id AND r.kind = 'SEARCH' AND r.search_profile_id IS NOT NULL
                ORDER BY r.id DESC LIMIT 1""").param("id", jobId).query(Long.class).optional();
    }

    /** An edit or regeneration revokes approval, so a DOCS_READY job goes back to SHORTLISTED. */
    public void revertDocsReady(long jobId) {
        jdbc.sql("UPDATE job SET status = 'SHORTLISTED' WHERE id = :id AND status = 'DOCS_READY'").param("id", jobId).update();
    }

    /** A clean fill moves the job to FILLED. Never to APPLIED: only the user decides that (hard rule 1). */
    public void markFilled(long jobId) {
        jdbc.sql("UPDATE job SET status = 'FILLED' WHERE id = :id AND status IN ('NEW', 'SHORTLISTED', 'DOCS_READY')")
                .param("id", jobId).update();
    }

    public void markDocsReady(long jobId) {
        jdbc.sql("UPDATE job SET status = 'DOCS_READY' WHERE id = :id AND status IN ('NEW', 'SHORTLISTED')")
                .param("id", jobId).update();
    }

    private Set<Long> ids(String json) {
        try {
            return new java.util.LinkedHashSet<>(mapper.readValue(json, new TypeReference<List<Long>>() { }));
        } catch (JsonProcessingException e) {
            return Set.of();
        }
    }

    // ---- scores ----

    public boolean hasScore(long jobId, long searchProfileId, String cvVersion) {
        return jdbc.sql("SELECT count(*) FROM job_score WHERE job_id = :j AND search_profile_id = :p AND cv_version = :v")
                .param("j", jobId).param("p", searchProfileId).param("v", cvVersion).query(Long.class).single() > 0;
    }

    public void saveScore(long jobId, long searchProfileId, String cvVersion, Score s) {
        try {
            jdbc.sql("""
                    INSERT INTO job_score (job_id, search_profile_id, cv_version, total, tech, experience, eligibility,
                        deal_breakers_json, fit_text, gaps_text, model)
                    VALUES (:j, :p, :v, :total, :tech, :exp, :elig, :deals::jsonb, :fit, :gaps, :model)
                    ON CONFLICT (job_id, search_profile_id, cv_version) DO UPDATE SET total = EXCLUDED.total,
                        tech = EXCLUDED.tech, experience = EXCLUDED.experience, eligibility = EXCLUDED.eligibility,
                        deal_breakers_json = EXCLUDED.deal_breakers_json, fit_text = EXCLUDED.fit_text,
                        gaps_text = EXCLUDED.gaps_text, model = EXCLUDED.model, scored_at = now()
                    """).param("j", jobId).param("p", searchProfileId).param("v", cvVersion).param("total", s.total())
                    .param("tech", s.tech()).param("exp", s.experience()).param("elig", s.eligibility())
                    .param("deals", mapper.writeValueAsString(s.dealBreakers())).param("fit", s.fit())
                    .param("gaps", s.gaps()).param("model", s.model()).update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- mapping ----

    private Row row(ResultSet rs, int n) throws SQLException {
        Score score = null;
        if (rs.getObject("total") != null) {
            score = new Score(rs.getInt("total"), rs.getInt("tech"), rs.getInt("score_experience"),
                    rs.getInt("eligibility"), strings(rs.getString("deal_breakers_json")), rs.getString("fit_text"),
                    rs.getString("gaps_text"), rs.getString("model"), instant(rs, "scored_at"));
        }
        return new Row(rs.getLong("id"), null, rs.getString("source"), rs.getString("source_id"), rs.getString("url"),
                rs.getString("apply_url"), rs.getString("title"), rs.getString("employer"),
                rs.getString("client_employer"), rs.getString("location"), rs.getString("municipality"),
                instant(rs, "published_at"), instant(rs, "deadline"), rs.getString("language"),
                rs.getString("employment_type"), rs.getBoolean("asks_for_new_graduates"),
                rs.getString("experience_required_text"), Status.valueOf(rs.getString("status")),
                rs.getString("notes"), rs.getBoolean("date_unverified"), rs.getString("evidence_url"), score);
    }

    private List<String> strings(String json) {
        try {
            return json == null ? List.of() : mapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
