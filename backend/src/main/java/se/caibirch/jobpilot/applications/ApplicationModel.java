package se.caibirch.jobpilot.applications;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

public final class ApplicationModel {

    private ApplicationModel() {
    }

    public record DocState(long id, int version, boolean approved) {
    }

    /** The newest document generation of the job: what Documents shows while it works and when it failed. */
    public record Generation(long runId, String status, String step, int progress, String message) {
    }

    /**
     * A selected job with its documents and where it stands. {@code submittedAt} is set only by the user's own click and
     * {@code submittedDocs} records which documents were used then.
     */
    public record View(long jobId, String title, String employer, String municipality, String url, String applyUrl,
                       String searchMode, String roleType, Integer score, String jobStatus, Instant selectedAt,
                       Instant submittedAt, DocState cv, DocState letter, Generation generation, JsonNode submittedDocs) {
    }
}
