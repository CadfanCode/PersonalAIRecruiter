package app.jobpilot.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * What JobPilot keeps (ADR 0025). Search runs and the ads they found are scratch data: they go when the program starts, when it
 * closes and when a new search begins. What stays is what the user chose to work on (an application with its documents, until
 * they delete it or submit it) and what they submitted (the record in Submitted applications, and the aktivitetsrapport log).
 * A deleted application or document is removed completely, including its files.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    /** Jobs nobody chose to keep: not selected as an application, not submitted, not in the activity log, not in a live run. */
    private static final String DISPOSABLE_JOBS = """
            SELECT j.id FROM job j
            WHERE NOT EXISTS (SELECT 1 FROM application a WHERE a.job_id = j.id)
              AND NOT EXISTS (SELECT 1 FROM submitted_application s WHERE s.job_id = j.id)
              AND NOT EXISTS (SELECT 1 FROM aktivitet_log l WHERE l.job_id = j.id)
              AND NOT EXISTS (SELECT 1 FROM run_job rj JOIN pipeline_run r ON r.id = rj.run_id
                              WHERE rj.job_id = j.id AND r.status IN ('QUEUED', 'RUNNING'))
            """;

    private final JdbcClient jdbc;

    public RetentionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Removes finished search runs, the ads only they found, and the search profiles nothing refers to any more. A run that a
     * selected application was chosen from is kept (it is where the application's score comes from) but only holds that
     * application's ad. Runs that are queued or running are never touched.
     */
    public synchronized void purgeSearchData() {
        int jobs = deleteJobs(DISPOSABLE_JOBS);
        int runs = jdbc.sql("""
                DELETE FROM pipeline_run
                WHERE kind = 'SEARCH' AND status NOT IN ('QUEUED', 'RUNNING')
                  AND NOT EXISTS (SELECT 1 FROM application a WHERE a.run_id = pipeline_run.id)
                """).update();
        int profiles = jdbc.sql("""
                DELETE FROM search_profile sp
                WHERE NOT EXISTS (SELECT 1 FROM pipeline_run r WHERE r.search_profile_id = sp.id)
                  AND NOT EXISTS (SELECT 1 FROM role_suggestion rs WHERE rs.search_profile_id = sp.id)
                  AND NOT EXISTS (SELECT 1 FROM document d WHERE d.search_profile_id = sp.id)
                """).update();
        if (jobs + runs + profiles > 0) {
            log.info("Cleared old search data: {} ads, {} runs, {} search profiles", jobs, runs, profiles);
        }
    }

    /** Removes these jobs completely: the job row (and with it documents, scores, snapshots and run links) and the PDF files. */
    public synchronized int deleteJobs(List<Long> jobIds) {
        if (jobIds.isEmpty()) {
            return 0;
        }
        return deleteJobs("SELECT id FROM job WHERE id IN (" + String.join(",", jobIds.stream().map(String::valueOf).toList()) + ")");
    }

    private int deleteJobs(String idQuery) {
        List<Long> ids = jdbc.sql(idQuery).query(Long.class).list();
        if (ids.isEmpty()) {
            return 0;
        }
        String in = String.join(",", ids.stream().map(String::valueOf).toList());
        List<String> files = jdbc.sql("SELECT pdf_path FROM document WHERE pdf_path IS NOT NULL AND job_id IN (" + in + ")")
                .query(String.class).list();
        // Documents-writing runs of these jobs go with them (their job_id cascades); a plain run link just goes.
        int n = jdbc.sql("DELETE FROM job WHERE id IN (" + in + ")").update();
        files.forEach(RetentionService::deleteFile);
        return n;
    }

    private static void deleteFile(String path) {
        try {
            Path file = Path.of(path);
            Files.deleteIfExists(file);
            Path dir = file.getParent();
            if (dir != null) {
                try (var left = Files.list(dir)) {
                    if (left.findAny().isEmpty()) {
                        Files.deleteIfExists(dir);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete {}: {}", path, e.toString());
        }
    }
}
