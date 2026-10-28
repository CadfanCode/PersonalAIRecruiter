package app.jobpilot.jobs;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import app.jobpilot.common.ApiExceptions.NotFoundException;
import app.jobpilot.jobs.JobModel.Detail;
import app.jobpilot.jobs.JobModel.Row;
import app.jobpilot.jobs.JobModel.Status;
import app.jobpilot.pipeline.RunRepository;

@Service
public class JobService {

    /** All filters are optional. {@code top} limits the result after sorting. */
    public record Query(String q, Status status, String language, Integer minScore, String sort, Integer top) {
    }

    private final JobRepository jobs;
    private final RunRepository runs;

    public JobService(JobRepository jobs, RunRepository runs) {
        this.jobs = jobs;
        this.runs = runs;
    }

    public List<Row> rowsOfRun(long runId, Query query) {
        var run = runs.find(runId).orElseThrow(() -> new NotFoundException("No run " + runId));
        if (run.searchProfileId() == null) {
            return List.of();
        }
        List<Row> all = run.options().discover() ? jobs.rowsOfDiscoverRun(runId) : jobs.rowsOfRun(runId, run.searchProfileId());
        List<Row> rows = all.stream().filter(r -> matches(r, query)).toList();
        rows = sorted(rows, query.sort());
        return query.top() == null ? rows : rows.stream().limit(query.top()).toList();
    }

    public Detail detail(long jobId, Long runId) {
        Long profileId = runId == null ? null : jobs.profileIdFor(jobId, runId).orElse(null);
        Row row = jobs.findRow(jobId, profileId).orElseThrow(() -> new NotFoundException("No job " + jobId));
        return new Detail(row, jobs.description(jobId).orElse(""), jobs.rawJson(jobId).orElse("{}"));
    }

    public void patch(long id, Status status, String notes) {
        if (!jobs.patch(id, status, notes)) {
            throw new NotFoundException("No job " + id);
        }
    }

    private static boolean matches(Row r, Query q) {
        if (q.status() != null && r.status() != q.status()) {
            return false;
        }
        if (q.language() != null && !q.language().equalsIgnoreCase(r.language())) {
            return false;
        }
        if (q.minScore() != null && (r.score() == null || r.score().total() < q.minScore())) {
            return false;
        }
        if (q.q() != null && !q.q().isBlank()) {
            String needle = q.q().toLowerCase(Locale.ROOT);
            String hay = String.join(" ", nz(r.title()), nz(r.employer()), nz(r.clientEmployer()), nz(r.municipality()))
                    .toLowerCase(Locale.ROOT);
            return hay.contains(needle);
        }
        return true;
    }

    private static List<Row> sorted(List<Row> rows, String sort) {
        if (sort == null || sort.equals("rank")) {
            return rows; // already ranked by the repository
        }
        Comparator<Row> byDeadline = Comparator.comparing(Row::deadline, Comparator.nullsLast(Comparator.naturalOrder()));
        Comparator<Row> byPublished = Comparator.comparing(Row::publishedAt, Comparator.nullsLast(Comparator.reverseOrder()));
        return rows.stream().sorted(switch (sort) {
            case "deadline" -> byDeadline;
            case "published" -> byPublished;
            default -> throw new IllegalArgumentException("sort must be rank, deadline or published");
        }).toList();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
