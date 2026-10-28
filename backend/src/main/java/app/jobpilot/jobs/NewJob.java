package app.jobpilot.jobs;

import java.time.Instant;

/** An ad as it comes out of a source (JobTech or web discovery), before it is stored. */
public record NewJob(String source, String sourceId, String url, String applyUrl, String title, String employer,
                     String clientEmployer, String location, String municipality, Instant publishedAt,
                     Instant deadline, String language, String employmentType, String descriptionText,
                     String rawJson, boolean dateUnverified, String evidenceUrl, double relevance,
                     boolean remoteHint) {

    public String dedupeKey() {
        return Dedupe.key(employer, title, municipality);
    }
}
