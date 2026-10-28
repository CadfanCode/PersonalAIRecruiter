package app.jobpilot.claude;

import java.util.Map;
import java.util.Optional;

/**
 * The parts of the app that can each use their own Claude model (Settings). Searching is cheap and wide, scoring is the
 * high-volume call, writing the CV and letter and filling forms reward a stronger model. A stage without a choice uses the default.
 */
public enum ModelStage {
    SEARCH("Search"),
    RANKING("Scoring"),
    CV_READING("Reading your CV"),
    DOCUMENTS("CV and cover letter"),
    AUTOFILL("Auto-fill");

    private static final Map<String, ModelStage> OF_TASK = Map.ofEntries(
            Map.entry("buildSearchProfile", SEARCH), Map.entry("suggestRoles", SEARCH), Map.entry("profileRoles", SEARCH),
            Map.entry("discoverJobsWeb", SEARCH), Map.entry("extractJob", SEARCH),
            Map.entry("scoreJob", RANKING),
            Map.entry("importCv", CV_READING),
            Map.entry("selectFacts", DOCUMENTS), Map.entry("writeCv", DOCUMENTS), Map.entry("writeLetter", DOCUMENTS),
            Map.entry("rewriteForViolations", DOCUMENTS), Map.entry("reviseDocument", DOCUMENTS),
            Map.entry("mapFormFields", AUTOFILL));

    private final String label;

    ModelStage(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** The stage a Claude task belongs to; empty for tasks that always use the default (the connection check). */
    public static Optional<ModelStage> ofTask(String taskName) {
        return Optional.ofNullable(taskName == null ? null : OF_TASK.get(taskName));
    }
}
