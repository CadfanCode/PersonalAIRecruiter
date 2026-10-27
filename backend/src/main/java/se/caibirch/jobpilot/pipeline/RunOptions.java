package se.caibirch.jobpilot.pipeline;

/**
 * What the user set when starting a run. Null means "use the setting default". {@code limit} is how
 * many ads are scored (the best candidates by relevance), not how many the search may return.
 * {@code mode} is TARGETED (the default), DISCOVER or MANUAL; {@code aggressive} widens a DISCOVER run.
 */
public record RunOptions(Integer maxAgeDays, Integer limit, Boolean includeWeb, Boolean autoContinue, String mode,
                         Boolean aggressive) {

    public boolean web() {
        return Boolean.TRUE.equals(includeWeb);
    }

    /** When true the run does not pause for the user to review the SearchProfile. */
    public boolean auto() {
        return Boolean.TRUE.equals(autoContinue);
    }

    public String modeName() {
        return mode == null || mode.isBlank() ? "TARGETED" : mode;
    }

    public boolean discover() {
        return "DISCOVER".equals(modeName());
    }

    public boolean aggressiveSearch() {
        return Boolean.TRUE.equals(aggressive);
    }
}
