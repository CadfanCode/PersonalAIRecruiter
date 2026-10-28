package app.jobpilot.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.jobpilot.jobs.JobModel.Score;

class JobScoringTest {

    static JobScoring.Raw raw(int skills, int exp, int elig, String fit, String gaps) {
        return new JobScoring.Raw(skills, 50, exp, 30, elig, 20, false, "", "sv", fit, gaps, List.of());
    }

    @Test
    void totalIsTheSumComputedInJava() {
        Score s = JobScoring.toScore(raw(41, 22, 17, "fit", "gaps"), "m", Instant.EPOCH);

        assertThat(s.total()).isEqualTo(80);
        assertThat(s.skills()).isEqualTo(41);
    }

    @Test
    void componentsAreClampedToTheRubric() {
        Score s = JobScoring.toScore(raw(70, 45, -5, "fit", "gaps"), "m", Instant.EPOCH);

        assertThat(s.skills()).isEqualTo(50);
        assertThat(s.experience()).isEqualTo(30);
        assertThat(s.eligibility()).isZero();
        assertThat(s.total()).isEqualTo(80);
    }

    @Test
    void longTextIsCutAt300CharactersOnAWordBoundary() {
        String longFit = "word ".repeat(100).trim();

        Score s = JobScoring.toScore(raw(1, 1, 1, longFit, "short"), "m", Instant.EPOCH);

        assertThat(s.fit().length()).isLessThanOrEqualTo(300);
        assertThat(s.fit()).endsWith("word…");
        assertThat(s.gaps()).isEqualTo("short");
    }
}
