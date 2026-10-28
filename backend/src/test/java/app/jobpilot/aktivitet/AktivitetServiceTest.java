package app.jobpilot.aktivitet;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.jobpilot.apply.PageScripts.SavedActivities;
import app.jobpilot.apply.PageScripts.SavedRow;
import app.jobpilot.jobs.JobModel.Row;
import app.jobpilot.jobs.JobModel.Status;

class AktivitetServiceTest {

    static Row row(String employer, String client) {
        return new Row(1, null, "JOBTECH", "1", null, null, "Dev", employer, client, null, "Stockholm", null, null, "sv", "Heltid", false, null,
                Status.APPLIED, null, false, null, null, null);
    }

    @Test
    void theEmployerLabelNamesTheEndClientInBracketsOnlyWhenThereIsOne() {
        assertThat(AktivitetService.employerLabel(row("Comstream AB", "Taxi Stockholm"))).isEqualTo("Comstream AB (Taxi Stockholm)");
        assertThat(AktivitetService.employerLabel(row("Friday ", null))).isEqualTo("Friday");
        assertThat(AktivitetService.employerLabel(row("Friday", "  "))).isEqualTo("Friday");
    }

    @Test
    void omfattningComesFromTheAdAndDefaultsToHeltid() {
        assertThat(AktivitetService.omfattning("Heltid")).isEqualTo("Heltid");
        assertThat(AktivitetService.omfattning("Deltid")).isEqualTo("Deltid");
        assertThat(AktivitetService.omfattning("Timanställning / vid behov")).isEqualTo("Timmar vid behov");
        assertThat(AktivitetService.omfattning("Vanlig anställning")).isEqualTo("Heltid");
        assertThat(AktivitetService.omfattning(null)).isEqualTo("Heltid");
    }

    @Test
    void aDuplicateIsTheSameEmployerOnTheSameDate() {
        var saved = new SavedActivities(List.of(new SavedRow("Sökt jobb Comstream AB (Taxi Stockholm), Stockholm", "2026-10-26"),
                new SavedRow("Sökt jobb Friday, Järfälla", "2026-10-25")), false);
        var entry = new AktivitetModel.Entry(1, "Dev", "Systemutvecklare/Programmerare", "Comstream AB (Taxi Stockholm)", "Stockholm",
                "Heltid", "Sverige", true, LocalDate.of(2026, 10, 26));

        assertThat(AktivitetService.isDuplicate(saved, entry)).isTrue();
        assertThat(AktivitetService.isDuplicate(saved, new AktivitetModel.Entry(1, "Dev", "x", "Comstream AB", "Stockholm", "Heltid", "Sverige",
                true, LocalDate.of(2026, 10, 26)))).as("the employer without the end client matches too").isTrue();
        assertThat(AktivitetService.isDuplicate(saved, new AktivitetModel.Entry(1, "Dev", "x", "Comstream AB", "Stockholm", "Heltid", "Sverige",
                true, LocalDate.of(2026, 10, 27)))).as("another date").isFalse();
        assertThat(AktivitetService.isDuplicate(saved, new AktivitetModel.Entry(1, "Dev", "x", "Saab AB", "Järfälla", "Heltid", "Sverige",
                true, LocalDate.of(2026, 10, 26)))).as("another employer").isFalse();
    }
}
