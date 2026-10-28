package app.jobpilot.apply;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** One row per fill pass: what was on the page and what was done (SPEC 4 {@code application_form_snapshot}). */
@Repository
public class SnapshotRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public SnapshotRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void save(long jobId, String url, List<FormField> fields, Object actions) {
        try {
            jdbc.sql("INSERT INTO application_form_snapshot (job_id, url, fields_json, actions_json) VALUES (:j, :u, :f::jsonb, :a::jsonb)")
                    .param("j", jobId).param("u", url).param("f", mapper.writeValueAsString(fields))
                    .param("a", mapper.writeValueAsString(actions)).update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public long count(long jobId) {
        return jdbc.sql("SELECT count(*) FROM application_form_snapshot WHERE job_id = :j").param("j", jobId).query(Long.class).single();
    }
}
