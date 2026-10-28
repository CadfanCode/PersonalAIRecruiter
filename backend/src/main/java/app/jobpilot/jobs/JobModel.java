package app.jobpilot.jobs;

import java.time.Instant;
import java.util.List;

public final class JobModel {

    private JobModel() {
    }

    public enum Status { NEW, SHORTLISTED, DOCS_READY, FILLED, APPLIED, LOGGED, REJECTED, ARCHIVED }

    public record Score(int total, int skills, int experience, int eligibility, List<String> dealBreakers, String fit,
                        String gaps, String model, Instant scoredAt) {
    }

    /** A job as shown in the results table. {@code rank} is null while the job has no score. */
    public record Row(long id, Integer rank, String source, String sourceId, String url, String applyUrl, String title,
                      String employer, String clientEmployer, String location, String municipality,
                      Instant publishedAt, Instant deadline, String language, String employmentType,
                      boolean asksForNewGraduates, String experienceRequiredText, Status status, String notes,
                      boolean dateUnverified, String evidenceUrl, Instant appliedAt, Score score,
                      String roleType, List<String> roleTypes) {

        /** A row of a targeted or manual run: no role types. */
        public Row(long id, Integer rank, String source, String sourceId, String url, String applyUrl, String title,
                   String employer, String clientEmployer, String location, String municipality, Instant publishedAt,
                   Instant deadline, String language, String employmentType, boolean asksForNewGraduates,
                   String experienceRequiredText, Status status, String notes, boolean dateUnverified,
                   String evidenceUrl, Instant appliedAt, Score score) {
            this(id, rank, source, sourceId, url, applyUrl, title, employer, clientEmployer, location, municipality,
                    publishedAt, deadline, language, employmentType, asksForNewGraduates, experienceRequiredText, status,
                    notes, dateUnverified, evidenceUrl, appliedAt, score, null, List.of());
        }

        Row withRank(Integer r) {
            return new Row(id, r, source, sourceId, url, applyUrl, title, employer, clientEmployer, location,
                    municipality, publishedAt, deadline, language, employmentType, asksForNewGraduates,
                    experienceRequiredText, status, notes, dateUnverified, evidenceUrl, appliedAt, score, roleType,
                    roleTypes);
        }

        /** The role the ad was scored against (primary) and every role that found it. */
        Row withRoles(String primary, List<String> all) {
            return new Row(id, rank, source, sourceId, url, applyUrl, title, employer, clientEmployer, location,
                    municipality, publishedAt, deadline, language, employmentType, asksForNewGraduates,
                    experienceRequiredText, status, notes, dateUnverified, evidenceUrl, appliedAt, score, primary, all);
        }
    }

    /** A row plus the full ad text, for the side drawer. */
    public record Detail(Row row, String description, String rawJson) {
    }
}
