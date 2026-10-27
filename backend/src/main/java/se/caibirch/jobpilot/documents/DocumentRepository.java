package se.caibirch.jobpilot.documents;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import se.caibirch.jobpilot.documents.DocumentModel.Kind;

@Repository
public class DocumentRepository {

    public record Row(long id, long jobId, Kind kind, String language, String contentJson, String html, String pdfPath,
                      int version, String reportJson, boolean approved, Instant createdAt, Instant updatedAt,
                      Long searchProfileId) {
    }

    /** Index row for the documents overview. */
    public record Overview(long jobId, String title, String employer, String language, boolean cv, boolean letter,
                           boolean cvApproved, boolean letterApproved, Instant updatedAt) {
    }

    private final JdbcClient jdbc;

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One current document per job and kind: a save or regeneration replaces it and bumps the version. */
    public long upsert(long jobId, Kind kind, String language, String contentJson, String html, String pdfPath,
                       String reportJson, Long searchProfileId) {
        return jdbc.sql("""
                INSERT INTO document (job_id, kind, language, content_json, html, pdf_path, validator_report_json,
                                      search_profile_id)
                VALUES (:job, :kind, :lang, :content::jsonb, :html, :pdf, :report::jsonb, :sp)
                ON CONFLICT (job_id, kind) DO UPDATE SET language = EXCLUDED.language, content_json = EXCLUDED.content_json,
                    html = EXCLUDED.html, pdf_path = EXCLUDED.pdf_path, validator_report_json = EXCLUDED.validator_report_json,
                    search_profile_id = coalesce(EXCLUDED.search_profile_id, document.search_profile_id),
                    version = document.version + 1, approved = FALSE, updated_at = now()
                RETURNING id
                """).param("job", jobId).param("kind", kind.name()).param("lang", language).param("content", contentJson)
                .param("html", html).param("pdf", pdfPath).param("report", reportJson).param("sp", searchProfileId)
                .query(Long.class).single();
    }

    public Optional<Row> find(long id) {
        return jdbc.sql("SELECT * FROM document WHERE id = :id").param("id", id).query(this::row).optional();
    }

    public Optional<Row> find(long jobId, Kind kind) {
        return jdbc.sql("SELECT * FROM document WHERE job_id = :j AND kind = :k").param("j", jobId).param("k", kind.name())
                .query(this::row).optional();
    }

    public void setApproved(long id, boolean approved, String reportJson) {
        jdbc.sql("UPDATE document SET approved = :a, validator_report_json = :r::jsonb, updated_at = now() WHERE id = :id")
                .param("a", approved).param("r", reportJson).param("id", id).update();
    }

    public List<Overview> overview() {
        return jdbc.sql("""
                SELECT j.id, j.title, j.employer, max(d.language) AS language,
                       bool_or(d.kind = 'CV') AS cv, bool_or(d.kind = 'LETTER') AS letter,
                       coalesce(bool_or(d.kind = 'CV' AND d.approved), FALSE) AS cv_ok,
                       coalesce(bool_or(d.kind = 'LETTER' AND d.approved), FALSE) AS letter_ok,
                       max(d.updated_at) AS updated
                FROM document d JOIN job j ON j.id = d.job_id GROUP BY j.id ORDER BY max(d.updated_at) DESC
                """).query((rs, n) -> new Overview(rs.getLong("id"), rs.getString("title"), rs.getString("employer"),
                rs.getString("language"), rs.getBoolean("cv"), rs.getBoolean("letter"), rs.getBoolean("cv_ok"),
                rs.getBoolean("letter_ok"), rs.getTimestamp("updated").toInstant())).list();
    }

    private Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        long sp = rs.getLong("search_profile_id");
        Long searchProfileId = rs.wasNull() ? null : sp;
        return new Row(rs.getLong("id"), rs.getLong("job_id"), Kind.valueOf(rs.getString("kind")), rs.getString("language"),
                rs.getString("content_json"), rs.getString("html"), rs.getString("pdf_path"), rs.getInt("version"),
                rs.getString("validator_report_json"), rs.getBoolean("approved"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), searchProfileId);
    }
}
