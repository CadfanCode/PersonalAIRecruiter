package se.caibirch.jobpilot.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import se.caibirch.jobpilot.jobs.JobModel.Row;
import se.caibirch.jobpilot.jobs.JobModel.Score;
import se.caibirch.jobpilot.jobs.JobModel.Status;

class JobRankingTest {

    static Row row(String title, Integer total, String deadline, String published) {
        Score score = total == null ? null : new Score(total, 0, 0, 0, List.of(), "", "", "m", Instant.EPOCH);
        return new Row(title.hashCode(), null, "JOBTECH", "id", null, null, title, "E", null, null, null,
                published == null ? null : Instant.parse(published), deadline == null ? null : Instant.parse(deadline),
                "sv", null, false, null, Status.NEW, null, false, null, null, score);
    }

    @Test
    void ranksByTotalThenDeadlineThenNewestAndPutsUnscoredLast() {
        List<Row> rows = new ArrayList<>(List.of(
                row("unscored", null, null, "2026-10-26T00:00:00Z"),
                row("80-late", 80, "2026-11-30T00:00:00Z", "2026-10-20T00:00:00Z"),
                row("90", 90, null, "2026-10-20T00:00:00Z"),
                row("80-soon", 80, "2026-11-02T00:00:00Z", "2026-10-20T00:00:00Z"),
                row("80-nodeadline-new", 80, null, "2026-10-25T00:00:00Z"),
                row("80-nodeadline-old", 80, null, "2026-10-21T00:00:00Z")));

        rows.sort(JobRepository.RANKING);

        assertThat(rows).extracting(Row::title).containsExactly("90", "80-soon", "80-late", "80-nodeadline-new",
                "80-nodeadline-old", "unscored");
    }
}
