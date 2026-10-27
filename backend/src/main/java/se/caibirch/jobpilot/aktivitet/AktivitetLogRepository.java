package se.caibirch.jobpilot.aktivitet;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import se.caibirch.jobpilot.aktivitet.AktivitetModel.Entry;
import se.caibirch.jobpilot.aktivitet.AktivitetModel.LogRow;
import se.caibirch.jobpilot.aktivitet.AktivitetModel.Result;

/** Every attempt to log a job gets a row, saved or not (SPEC 4 {@code aktivitet_log}). */
@Repository
public class AktivitetLogRepository {

    private final JdbcClient jdbc;

    public AktivitetLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long runId, Entry e, Result result, String message, Instant savedAt) {
        jdbc.sql("""
                INSERT INTO aktivitet_log (job_id, run_id, yrkesroll, employer_label, municipality, omfattning, var_finns, answered_ad,
                                           applied_date, saved_at, result, message)
                VALUES (:job, :run, :yr, :emp, :mun, :om, :var, :ann, :date, :saved, :result, :msg)
                """).param("job", e.jobId()).param("run", runId).param("yr", e.yrkesroll()).param("emp", e.employerLabel())
                .param("mun", e.municipality()).param("om", e.omfattning()).param("var", e.varFinns()).param("ann", e.answeredAd())
                .param("date", Date.valueOf(e.appliedDate())).param("saved", savedAt == null ? null : Timestamp.from(savedAt))
                .param("result", result.name()).param("msg", message).update();
    }

    public boolean hasSaved(long jobId) {
        return jdbc.sql("SELECT count(*) FROM aktivitet_log WHERE job_id = :j AND result = 'SAVED'").param("j", jobId)
                .query(Long.class).single() > 0;
    }

    public List<LogRow> all() {
        return jdbc.sql("""
                SELECT l.*, j.title FROM aktivitet_log l JOIN job j ON j.id = l.job_id ORDER BY l.id DESC LIMIT 500
                """).query((rs, n) -> new LogRow(rs.getLong("id"), rs.getLong("job_id"), (Long) rs.getObject("run_id"), rs.getString("title"),
                rs.getString("yrkesroll"), rs.getString("employer_label"), rs.getString("municipality"), rs.getString("omfattning"),
                rs.getString("var_finns"), rs.getBoolean("answered_ad"),
                rs.getDate("applied_date") == null ? null : rs.getDate("applied_date").toLocalDate(),
                rs.getTimestamp("saved_at") == null ? null : rs.getTimestamp("saved_at").toInstant(), rs.getString("result"),
                rs.getString("message"), rs.getTimestamp("created_at").toInstant())).list();
    }
}
