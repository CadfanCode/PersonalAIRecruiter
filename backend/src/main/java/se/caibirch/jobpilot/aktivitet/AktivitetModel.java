package se.caibirch.jobpilot.aktivitet;

import java.time.Instant;
import java.time.LocalDate;

public final class AktivitetModel {

    private AktivitetModel() {
    }

    /** What will be written for one job: all seven form values, editable by the user before anything is saved. */
    public record Entry(long jobId, String title, String yrkesroll, String employerLabel, String municipality,
                        String omfattning, String varFinns, boolean answeredAd, LocalDate appliedDate) {
    }

    /** One row of the confirmation table: the proposed entry, or the reason the job cannot be logged. */
    public record Plan(long jobId, String title, Entry entry, String ineligible) {
    }

    public enum Result { SAVED, DUPLICATE, SKIPPED, UNCONFIRMED, FAILED }

    public record LogRow(long id, long jobId, Long runId, String title, String yrkesroll, String employerLabel, String municipality,
                         String omfattning, String varFinns, boolean answeredAd, LocalDate appliedDate, Instant savedAt,
                         String result, String message, Instant createdAt) {
    }

    public record Reminder(boolean active, int day, int daysLeft, String forMonth, String message) {
    }
}
