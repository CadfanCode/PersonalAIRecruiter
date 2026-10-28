package app.jobpilot.aktivitet;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import app.jobpilot.aktivitet.AktivitetModel.Reminder;

class ReminderServiceTest {

    @Test
    void theReportWindowOpensOnTheFirstAndClosesOnTheSixteenth() {
        Reminder first = ReminderService.reminder(LocalDate.of(2026, 11, 1));
        Reminder last = ReminderService.reminder(LocalDate.of(2026, 11, 16));
        Reminder after = ReminderService.reminder(LocalDate.of(2026, 11, 17));

        assertThat(first.active()).isTrue();
        assertThat(first.daysLeft()).isEqualTo(15);
        assertThat(first.forMonth()).isEqualTo("2026-10");
        assertThat(first.message()).contains("October 2026", "15 days left", "JobPilot never submits the report");
        assertThat(last.active()).isTrue();
        assertThat(last.daysLeft()).isZero();
        assertThat(last.message()).contains("today is the last day");
        assertThat(after.active()).isFalse();
        assertThat(after.message()).isEmpty();
    }

    @Test
    void januaryRemindsAboutDecember() {
        Reminder r = ReminderService.reminder(LocalDate.of(2027, 1, 5));

        assertThat(r.forMonth()).isEqualTo("2026-12");
        assertThat(r.message()).contains("December 2026", "11 days left");
        assertThat(ReminderService.reminder(LocalDate.of(2026, 11, 15)).message()).contains("1 day left");
    }
}
