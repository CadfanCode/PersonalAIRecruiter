package se.caibirch.jobpilot.applications;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The frozen record of what was submitted (V11 {@code submitted_application}). */
@Repository
public class SubmittedApplicationRepository {

    /** The list row: no ad text, no content, no PDF bytes. {@code reportedAt} comes from the aktivitet log. */
    public record Summary(long id, Long jobId, String title, String employer, String municipality, String url, String applyUrl,
                          String language, String searchMode, String roleType, Integer scoreTotal, Instant submittedAt,
                          Integer cvVersion, String cvFile, Integer letterVersion, String letterFile, Instant reportedAt) {
    }

    public record Detail(Summary summary, String adText, String cvContentJson, String letterContentJson) {
    }

    /** Everything the snapshot is made of; PDFs may be null when a document did not exist. */
    public record NewSnapshot(long jobId, String title, String employer, String municipality, String url, String applyUrl,
                              String language, String adText, String searchMode, String roleType, Integer scoreTotal,
                              Instant submittedAt, Integer cvVersion, String cvFile, String cvContentJson, byte[] cvPdf,
                              Integer letterVersion, String letterFile, String letterContentJson, byte[] letterPdf) {
    }

    private static final String SUMMARY = """
            SELECT s.id, s.job_id, s.title, s.employer, s.municipality, s.url, s.apply_url, s.language, s.search_mode, s.role_type,
                   s.score_total, s.submitted_at, s.cv_version, s.cv_file, s.letter_version, s.letter_file,
                   (SELECT max(l.saved_at) FROM aktivitet_log l WHERE l.job_id = s.job_id AND l.result = 'SAVED') AS reported_at
            FROM submitted_application s
            """;

    private final JdbcClient jdbc;

    public SubmittedApplicationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsForJob(long jobId) {
        return jdbc.sql("SELECT count(*) FROM submitted_application WHERE job_id = :j").param("j", jobId).query(Long.class).single() > 0;
    }

    /** Idempotent: a job already recorded keeps its first snapshot. */
    public void insert(NewSnapshot s) {
        jdbc.sql("""
                INSERT INTO submitted_application (job_id, title, employer, municipality, url, apply_url, language, ad_text, search_mode,
                    role_type, score_total, submitted_at, cv_version, cv_file, cv_content_json, cv_pdf, letter_version, letter_file,
                    letter_content_json, letter_pdf)
                VALUES (:job, :title, :employer, :municipality, :url, :applyUrl, :language, :adText, :mode, :role, :score, :at,
                    :cvVersion, :cvFile, :cvContent::jsonb, :cvPdf, :letterVersion, :letterFile, :letterContent::jsonb, :letterPdf)
                ON CONFLICT (job_id) DO NOTHING
                """)
                .param("job", s.jobId()).param("title", s.title()).param("employer", s.employer()).param("municipality", s.municipality())
                .param("url", s.url()).param("applyUrl", s.applyUrl()).param("language", s.language()).param("adText", s.adText())
                .param("mode", s.searchMode()).param("role", s.roleType()).param("score", s.scoreTotal())
                .param("at", Timestamp.from(s.submittedAt())).param("cvVersion", s.cvVersion()).param("cvFile", s.cvFile())
                .param("cvContent", s.cvContentJson()).param("cvPdf", s.cvPdf()).param("letterVersion", s.letterVersion())
                .param("letterFile", s.letterFile()).param("letterContent", s.letterContentJson()).param("letterPdf", s.letterPdf())
                .update();
    }

    public void deleteForJob(long jobId) {
        jdbc.sql("DELETE FROM submitted_application WHERE job_id = :j").param("j", jobId).update();
    }

    public List<Summary> all() {
        return jdbc.sql(SUMMARY + " ORDER BY s.submitted_at DESC, s.id DESC").query(this::summary).list();
    }

    public Optional<Summary> find(long id) {
        return jdbc.sql(SUMMARY + " WHERE s.id = :id").param("id", id).query(this::summary).optional();
    }

    public Optional<Summary> findByJob(long jobId) {
        return jdbc.sql(SUMMARY + " WHERE s.job_id = :j").param("j", jobId).query(this::summary).optional();
    }

    public Optional<Detail> detail(long id) {
        return jdbc.sql("SELECT ad_text, cv_content_json::text AS cv_json, letter_content_json::text AS letter_json FROM submitted_application WHERE id = :id")
                .param("id", id).query((rs, n) -> new String[] {rs.getString("ad_text"), rs.getString("cv_json"), rs.getString("letter_json")})
                .optional().flatMap(parts -> find(id).map(s -> new Detail(s, parts[0], parts[1], parts[2])));
    }

    /** The PDF bytes as they were when submitted; "cv" or "letter". */
    public Optional<byte[]> pdf(long id, String kind) {
        String column = "cv".equals(kind) ? "cv_pdf" : "letter_pdf";
        return jdbc.sql("SELECT " + column + " FROM submitted_application WHERE id = :id").param("id", id)
                .query((rs, n) -> rs.getBytes(1)).optional().filter(b -> b != null);
    }

    private Summary summary(ResultSet rs, int n) throws SQLException {
        long job = rs.getLong("job_id");
        Long jobId = rs.wasNull() ? null : job;
        int score = rs.getInt("score_total");
        Integer total = rs.wasNull() ? null : score;
        int cv = rs.getInt("cv_version");
        Integer cvVersion = rs.wasNull() ? null : cv;
        int letter = rs.getInt("letter_version");
        Integer letterVersion = rs.wasNull() ? null : letter;
        Timestamp reported = rs.getTimestamp("reported_at");
        return new Summary(rs.getLong("id"), jobId, rs.getString("title"), rs.getString("employer"), rs.getString("municipality"),
                rs.getString("url"), rs.getString("apply_url"), rs.getString("language"), rs.getString("search_mode"),
                rs.getString("role_type"), total, rs.getTimestamp("submitted_at").toInstant(), cvVersion, rs.getString("cv_file"),
                letterVersion, rs.getString("letter_file"), reported == null ? null : reported.toInstant());
    }
}
