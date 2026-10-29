package app.jobpilot.applications;

import java.time.Instant;
import java.util.List;

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
    public record View(long jobId, Long runId, String title, String employer, String municipality, String url, String applyUrl,
                       String searchMode, String roleType, Integer score, String jobStatus, Instant selectedAt,
                       Instant submittedAt, DocState cv, DocState letter, Generation generation, JsonNode submittedDocs,
                       boolean readyToApply, List<String> blockers, boolean sentToApply) {
    }

    /** The outcome of "Approve all" for one document: approved now, already approved, or refused with the reason. */
    public record ApprovalResult(long jobId, String title, String kind, boolean approved, boolean alreadyApproved, String reason) {
    }

    public record Skipped(long jobId, String title, String reason) {
    }

    /** What "Apply" did: the tabs that started, and the applications that did not proceed, each with the reason. */
    public record ApplyResult(List<app.jobpilot.apply.TabState.View> started, List<Skipped> skipped) {
    }
}
