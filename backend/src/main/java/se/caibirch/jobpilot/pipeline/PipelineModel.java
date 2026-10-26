package se.caibirch.jobpilot.pipeline;

import java.time.Instant;

import se.caibirch.jobpilot.search.SearchProfile;

public final class PipelineModel {

    private PipelineModel() {
    }

    public enum Status { QUEUED, RUNNING, WAITING_FOR_USER, DONE, FAILED, CANCELLED }

    /** Steps in order: PROFILE, SEARCH, RANK, DONE. */
    public static final class Step {
        public static final String PROFILE = "PROFILE";
        public static final String SEARCH = "SEARCH";
        public static final String RANK = "RANK";
        public static final String DONE = "DONE";

        private Step() {
        }
    }

    public record Run(long id, Long searchProfileId, Status status, String step, int progress, String message,
                      Instant startedAt, Instant finishedAt, RunOptions options, String prompt) {
    }

    /** A run with its current search profile, as the UI polls it. */
    public record RunView(Run run, SearchProfile profile) {
    }
}
