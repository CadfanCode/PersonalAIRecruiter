package se.caibirch.jobpilot.pipeline;

/**
 * What the user set when starting a run. Null means "use the setting default". {@code limit} is how
 * many ads are scored (the best candidates by relevance), not how many the search may return.
 */
public record RunOptions(Integer maxAgeDays, Integer limit, Boolean includeWeb, Boolean autoContinue) {

    public boolean web() {
        return Boolean.TRUE.equals(includeWeb);
    }

    /** When true the run does not pause for the user to review the SearchProfile. */
    public boolean auto() {
        return Boolean.TRUE.equals(autoContinue);
    }
}
