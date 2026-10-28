package app.jobpilot.documents;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DateLabelsTest {

    @Test
    void usesYearsAndPlainHyphens() {
        assertThat(DateLabels.label("2018-01", "2021-01", false, "sv")).isEqualTo("2018-2021");
        assertThat(DateLabels.label("2026-01", "2026-05", false, "sv")).isEqualTo("2026");
        assertThat(DateLabels.label("2024", "2026", false, "en")).isEqualTo("2024-2026");
        assertThat(DateLabels.label("2018-01", "2021-01", false, "sv")).doesNotContain("–").doesNotContain("—");
    }

    @Test
    void ongoingAndPartialDates() {
        assertThat(DateLabels.label("2025-06", null, true, "sv")).isEqualTo("2025-nu");
        assertThat(DateLabels.label("2025-06", null, true, "en")).isEqualTo("2025-present");
        assertThat(DateLabels.label(null, null, true, "en")).isEqualTo("present");
        assertThat(DateLabels.label("2019", null, false, "sv")).isEqualTo("2019");
        assertThat(DateLabels.label(null, "2026-08", false, "sv")).isEqualTo("till 2026");
        assertThat(DateLabels.label(null, null, false, "sv")).isEmpty();
    }
}
