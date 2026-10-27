package se.caibirch.jobpilot.pipeline;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import se.caibirch.jobpilot.pipeline.PipelineModel.Run;
import se.caibirch.jobpilot.pipeline.PipelineModel.Status;

@Repository
public class RunRepository {

    private static final String SELECT = """
            SELECT r.*, sp.prompt AS sp_prompt FROM pipeline_run r LEFT JOIN search_profile sp ON sp.id = r.search_profile_id
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public RunRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public long create(RunOptions options, Instant now) {
        try {
            return jdbc.sql("""
                    INSERT INTO pipeline_run (status, step, progress, message, started_at, options_json)
                    VALUES ('QUEUED', 'PROFILE', 0, 'Queued', :now, :o::jsonb) RETURNING id
                    """).param("now", Timestamp.from(now)).param("o", mapper.writeValueAsString(options))
                    .query(Long.class).single();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public void update(long id, Status status, String step, int progress, String message) {
        jdbc.sql("UPDATE pipeline_run SET status = :s, step = :step, progress = :p, message = :m WHERE id = :id")
                .param("s", status.name()).param("step", step).param("p", progress).param("m", message)
                .param("id", id).update();
    }

    public void progress(long id, int progress, String message) {
        jdbc.sql("UPDATE pipeline_run SET progress = :p, message = :m WHERE id = :id")
                .param("p", progress).param("m", message).param("id", id).update();
    }

    public void finish(long id, Status status, String message, Instant now) {
        jdbc.sql("""
                UPDATE pipeline_run SET status = :s, message = :m, finished_at = :now,
                    step = CASE WHEN :s = 'DONE' THEN 'DONE' ELSE step END,
                    progress = CASE WHEN :s = 'DONE' THEN 100 ELSE progress END
                WHERE id = :id
                """).param("s", status.name()).param("m", message).param("now", Timestamp.from(now)).param("id", id).update();
    }

    public void setSearchProfile(long id, long searchProfileId) {
        jdbc.sql("UPDATE pipeline_run SET search_profile_id = :sp WHERE id = :id")
                .param("sp", searchProfileId).param("id", id).update();
    }

    public Optional<Run> find(long id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query(this::map).optional();
    }

    /** Search runs only; document runs belong to a job and have their own page. */
    public List<Run> list(int limit) {
        return jdbc.sql(SELECT + " WHERE r.kind = 'SEARCH' ORDER BY r.id DESC LIMIT :n").param("n", limit).query(this::map).list();
    }

    public long createDocumentsRun(long jobId, long searchProfileId, Instant now) {
        return jdbc.sql("""
                INSERT INTO pipeline_run (kind, job_id, search_profile_id, status, step, progress, message, started_at)
                VALUES ('DOCUMENTS', :job, :sp, 'QUEUED', 'SELECT', 0, 'Queued', :now) RETURNING id
                """).param("job", jobId).param("sp", searchProfileId).param("now", Timestamp.from(now))
                .query(Long.class).single();
    }

    /** Runs that were RUNNING or QUEUED when the app stopped can never finish. WAITING_FOR_USER runs stay. */
    public int failInterrupted(Instant now) {
        return jdbc.sql("""
                UPDATE pipeline_run SET status = 'FAILED', message = 'Interrupted by an application restart',
                    finished_at = :now WHERE status IN ('RUNNING', 'QUEUED')
                """).param("now", Timestamp.from(now)).update();
    }

    private static Long jobIdOf(ResultSet rs) throws SQLException {
        long id = rs.getLong("job_id");
        return rs.wasNull() ? null : id;
    }

    private Run map(ResultSet rs, int n) throws SQLException {
        try {
            long sp = rs.getLong("search_profile_id");
            Long searchProfileId = rs.wasNull() ? null : sp; // must be read right after the column
            return new Run(rs.getLong("id"), searchProfileId, Status.valueOf(rs.getString("status")),
                    rs.getString("step"), rs.getInt("progress"), rs.getString("message"),
                    rs.getTimestamp("started_at").toInstant(),
                    rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                    mapper.readValue(rs.getString("options_json"), RunOptions.class), rs.getString("sp_prompt"),
                    rs.getString("kind"), jobIdOf(rs));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt options_json for run " + rs.getLong("id"), e);
        }
    }
}
