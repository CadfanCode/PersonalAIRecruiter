package se.caibirch.jobpilot.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import se.caibirch.jobpilot.cvdb.CvModel.Entry;
import se.caibirch.jobpilot.cvdb.CvModel.Kind;

class GapNoteTest {

    static final YearMonth NOW = YearMonth.of(2026, 10);

    static Entry job(long id, Kind kind, String start, String end, boolean ongoing, boolean hidden) {
        return new Entry(id, kind, "T" + id, null, "Org", null, start, end, ongoing, null, null, 0, hidden, List.of(), List.of());
    }

    static Entry job(long id, String start, String end) {
        return job(id, Kind.WORK, start, end, false, false);
    }

    @Test
    void anOmittedJobThatLeavesMoreThanSixMonthsUncoveredGetsOneLine() {
        var all = List.of(job(1, "2022-01", "2026-05"), job(2, "2019-01", "2021-01"));

        assertThat(GapNote.compute(all, Set.of(1L), "sv", NOW)).contains("Övrig anställning 2019-2021, referenser lämnas på begäran");
        assertThat(GapNote.compute(all, Set.of(1L), "en", NOW)).contains("Other employment 2019-2021, available on request");
    }

    @Test
    void anOmittedJobCoveredByASelectedOneNeedsNoLine() {
        var all = List.of(job(1, "2019-01", "2022-01"), job(2, "2019-06", "2021-01"));

        assertThat(GapNote.compute(all, Set.of(1L), "sv", NOW)).isEmpty();
    }

    @Test
    void shortOmissionsUnderSixMonthsAreNotMentioned() {
        var all = List.of(job(1, "2022-01", "2026-05"), job(2, "2021-08", "2021-12"));

        assertThat(GapNote.compute(all, Set.of(1L), "sv", NOW)).isEmpty();
    }

    @Test
    void yearOnlyDatesAndOngoingJobsAreHandled() {
        var all = List.of(job(1, Kind.WORK, "2025-06", null, true, false), job(2, "2018", "2021"));

        assertThat(GapNote.compute(all, Set.of(1L), "sv", NOW)).contains("Övrig anställning 2018-2021, referenser lämnas på begäran");
        assertThat(GapNote.compute(List.of(job(2, "2020", "2020")), Set.of(), "sv", NOW)).contains("Övrig anställning 2020, referenser lämnas på begäran");
    }

    @Test
    void severalOmittedJobsAreSummarisedAsOneRange() {
        var all = List.of(job(1, "2024-01", "2026-05"), job(2, "2018-01", "2019-06"), job(3, "2020-01", "2021-12"));

        assertThat(GapNote.compute(all, Set.of(1L), "sv", NOW)).contains("Övrig anställning 2018-2021, referenser lämnas på begäran");
    }

    @Test
    void separatePeriodsAreListedSeparatelyNotAsOneMisleadingRange() {
        var all = List.of(job(1, "2022-01", "2024-12"), job(2, "2018-01", "2021-01"), job(3, "2025-01", "2025-12"));

        assertThat(GapNote.compute(all, Set.of(1L), "en", NOW)).contains("Other employment 2018-2021 and 2025, available on request");
        assertThat(GapNote.compute(all, Set.of(1L), "sv", NOW)).contains("Övrig anställning 2018-2021 och 2025, referenser lämnas på begäran");
        var three = List.of(job(1, "2022-01", "2022-12"), job(2, "2010-01", "2011-06"), job(3, "2015-01", "2016-06"), job(4, "2024-01", "2025-06"));
        assertThat(GapNote.compute(three, Set.of(1L), "en", NOW)).contains("Other employment 2010-2011, 2015-2016 and 2024-2025, available on request");
    }

    @Test
    void hiddenEntriesUndatedEntriesAndNonEmploymentAreIgnored() {
        var all = List.of(job(1, "2024-01", "2026-05"), job(2, Kind.WORK, "2018-01", "2020-01", false, true),
                job(3, Kind.WORK, null, null, false, false), job(4, Kind.EDUCATION, "2014-01", "2018-01", false, false),
                job(5, Kind.WORK, "2012-01", null, false, false));

        assertThat(GapNote.compute(all, Set.of(1L), "sv", NOW)).isEmpty();
    }
}
