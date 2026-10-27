package se.caibirch.jobpilot.discovery;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RoleRepository {

    /** A stored role. {@code profileJson} holds Claude's role-specific search fields; {@code searchProfileId} the profile used. */
    public record Row(long id, long runId, String title, String category, Integer matchScore, String reasoning, String gaps,
                      boolean selected, boolean userAdded, String profileJson, Long searchProfileId, int jobCount) {
    }

    private static final String SELECT = """
            SELECT r.*, (SELECT count(*) FROM run_job_role x WHERE x.role_suggestion_id = r.id) AS job_count
            FROM role_suggestion r
            """;

    private final JdbcClient jdbc;

    public RoleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long runId, String title, String category, Integer score, String reasoning, String gaps, boolean selected,
                       boolean userAdded, String profileJson, int sortOrder) {
        return jdbc.sql("""
                INSERT INTO role_suggestion (run_id, title, category, match_score, reasoning, gaps, selected, user_added, profile_json, sort_order)
                VALUES (:run, :title, :cat, :score, :why, :gaps, :sel, :added, :profile::jsonb, :sort) RETURNING id
                """).param("run", runId).param("title", title).param("cat", category).param("score", score).param("why", reasoning)
                .param("gaps", gaps).param("sel", selected).param("added", userAdded).param("profile", profileJson)
                .param("sort", sortOrder).query(Long.class).single();
    }

    public List<Row> list(long runId) {
        return jdbc.sql(SELECT + " WHERE r.run_id = :run ORDER BY r.sort_order, r.id").param("run", runId).query(this::row).list();
    }

    public Optional<Row> find(long id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query(this::row).optional();
    }

    public void setSelected(long id, boolean selected) {
        jdbc.sql("UPDATE role_suggestion SET selected = :s WHERE id = :id").param("s", selected).param("id", id).update();
    }

    /** A renamed role needs fresh search fields: the old ones described another role. */
    public void rename(long id, String title) {
        jdbc.sql("UPDATE role_suggestion SET title = :t, profile_json = NULL, search_profile_id = NULL, user_added = TRUE WHERE id = :id")
                .param("t", title).param("id", id).update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM role_suggestion WHERE id = :id").param("id", id).update();
    }

    public void setProfileJson(long id, String json) {
        jdbc.sql("UPDATE role_suggestion SET profile_json = :j::jsonb WHERE id = :id").param("j", json).param("id", id).update();
    }

    public void setSearchProfile(long id, long searchProfileId) {
        jdbc.sql("UPDATE role_suggestion SET search_profile_id = :p WHERE id = :id").param("p", searchProfileId).param("id", id).update();
    }

    public int nextSortOrder(long runId) {
        return jdbc.sql("SELECT coalesce(max(sort_order), -1) + 1 FROM role_suggestion WHERE run_id = :run").param("run", runId)
                .query(Integer.class).single();
    }

    private Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        int score = rs.getInt("match_score");
        Integer matchScore = rs.wasNull() ? null : score;
        long sp = rs.getLong("search_profile_id");
        Long searchProfileId = rs.wasNull() ? null : sp;
        return new Row(rs.getLong("id"), rs.getLong("run_id"), rs.getString("title"), rs.getString("category"), matchScore,
                rs.getString("reasoning"), rs.getString("gaps"), rs.getBoolean("selected"), rs.getBoolean("user_added"),
                rs.getString("profile_json"), searchProfileId, rs.getInt("job_count"));
    }
}
