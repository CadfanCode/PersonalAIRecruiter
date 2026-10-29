package app.jobpilot.applications;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApplicationRepository {

    /** The columns of one application row joined with its job and the job's newest score for the run it was selected in. */
    public record Row(long jobId, Long runId, String searchMode, String roleType, Instant selectedAt, Instant submittedAt,
                      String docsJson, String title, String employer, String municipality, String url, String applyUrl,
                      String jobStatus, Integer score, Instant sentToApplyAt) {
    }

    private static final String SELECT = """
            SELECT a.*, j.title, j.employer, j.municipality, j.url, j.apply_url, j.status AS job_status,
                   (SELECT s.total FROM job_score s JOIN pipeline_run r ON r.id = a.run_id
                     LEFT JOIN run_job rj ON rj.run_id = r.id AND rj.job_id = a.job_id
                     LEFT JOIN role_suggestion rs ON rs.id = rj.role_suggestion_id
                     WHERE s.job_id = a.job_id AND s.search_profile_id = coalesce(rs.search_profile_id, r.search_profile_id)
                     ORDER BY s.scored_at DESC LIMIT 1) AS score
            FROM application a JOIN job j ON j.id = a.job_id
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ApplicationRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Selecting an already selected job changes nothing. A fresh NEW job becomes SHORTLISTED. */
    public void select(long jobId, Long runId, String searchMode, String roleType) {
        jdbc.sql("""
                INSERT INTO application (job_id, run_id, search_mode, role_type) VALUES (:j, :r, :m, :t)
                ON CONFLICT (job_id) DO NOTHING
                """).param("j", jobId).param("r", runId).param("m", searchMode).param("t", roleType).update();
        jdbc.sql("UPDATE job SET status = 'SHORTLISTED' WHERE id = :j AND status = 'NEW'").param("j", jobId).update();
    }

    public boolean remove(long jobId) {
        return jdbc.sql("DELETE FROM application WHERE job_id = :j").param("j", jobId).update() > 0;
    }

    public Optional<Row> find(long jobId) {
        return jdbc.sql(SELECT + " WHERE a.job_id = :j").param("j", jobId).query(this::row).optional();
    }

    public List<Row> all() {
        return jdbc.sql(SELECT + " ORDER BY a.selected_at DESC, a.job_id").query(this::row).list();
    }

    public void markSubmitted(long jobId, Instant now, JsonNode docs) {
        try {
            jdbc.sql("UPDATE application SET submitted_at = coalesce(submitted_at, :now), docs_json = coalesce(docs_json, :d::jsonb) WHERE job_id = :j")
                    .param("now", Timestamp.from(now)).param("d", mapper.writeValueAsString(docs)).param("j", jobId).update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Marks the applications the user sent on to the Apply stage; they stay there until removed or submitted. */
    public void sendToApply(List<Long> jobIds, Instant now) {
        for (long id : jobIds) {
            jdbc.sql("UPDATE application SET sent_to_apply_at = coalesce(sent_to_apply_at, :now) WHERE job_id = :j")
                    .param("now", Timestamp.from(now)).param("j", id).update();
        }
    }

    public void withdrawFromApply(long jobId) {
        jdbc.sql("UPDATE application SET sent_to_apply_at = NULL WHERE job_id = :j").param("j", jobId).update();
    }

    public void clearSubmitted(long jobId) {
        jdbc.sql("UPDATE application SET submitted_at = NULL, docs_json = NULL WHERE job_id = :j").param("j", jobId).update();
        jdbc.sql("UPDATE job SET status = 'FILLED' WHERE id = :j AND status = 'APPLIED'").param("j", jobId).update();
    }

    private Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        long run = rs.getLong("run_id");
        Long runId = rs.wasNull() ? null : run;
        int score = rs.getInt("score");
        Integer total = rs.wasNull() ? null : score;
        return new Row(rs.getLong("job_id"), runId, rs.getString("search_mode"), rs.getString("role_type"),
                rs.getTimestamp("selected_at").toInstant(),
                rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant(),
                rs.getString("docs_json"), rs.getString("title"), rs.getString("employer"), rs.getString("municipality"),
                rs.getString("url"), rs.getString("apply_url"), rs.getString("job_status"), total,
                rs.getTimestamp("sent_to_apply_at") == null ? null : rs.getTimestamp("sent_to_apply_at").toInstant());
    }
}
