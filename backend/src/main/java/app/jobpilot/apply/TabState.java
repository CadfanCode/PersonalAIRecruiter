package app.jobpilot.apply;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import app.jobpilot.apply.ActionExecutor.Done;
import app.jobpilot.apply.ActionValidator.Skipped;
import app.jobpilot.apply.FormActions.Question;

/** What the monitor shows for one application tab. Mutated by the fill thread, read by the status endpoint. */
public final class TabState {

    public enum Status {
        QUEUED, OPENING, FILLING, WAITING_FOR_SIGN_IN, NEEDS_MANUAL_APPLY, NEEDS_ANSWERS, READY_FOR_USER, SESSION_EXPIRED,
        EMAIL_ONLY, NO_APPLY_LINK, APPLY_MANUALLY, CLOSED, ERROR
    }

    /** The steps the progress bar shows, in order. */
    public enum Stage {
        OPENING_AD("Opening job ad"), FINDING_FORM("Finding application form"), PERSONAL_DETAILS("Filling personal details"),
        UPLOADING("Uploading CV and cover letter"), QUESTIONS("Answering questions"), READY_FOR_REVIEW("Ready for your review");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * The JSON the UI gets. {@code stage} is where the work is now; {@code failedStage} and {@code failure} say where and why it
     * stopped; {@code reasons} are the rules that decided what kind of page this is (why a sign-in prompt was or was not shown).
     */
    public record View(long jobId, String title, String employer, String url, Status status, String message,
                       List<Done> done, List<Skipped> skipped, List<Question> questions, int passes, String phase,
                       String signInHost, boolean loginFilled, String stage, String stageLabel, String failedStage, String failure,
                       List<String> reasons, boolean browserVisible) {
    }

    /**
     * The six things the user sees (plus queued and closed), whatever the tab is doing underneath: FILLING, NEEDS_LOGIN,
     * NEEDS_ATTENTION, READY_FOR_SUBMISSION, FAILED, QUEUED, CLOSED. SUBMITTED comes from the application, not from the tab.
     */
    public static String phaseOf(Status s) {
        return switch (s) {
            case QUEUED -> "QUEUED";
            case OPENING, FILLING -> "FILLING";
            case WAITING_FOR_SIGN_IN -> "NEEDS_LOGIN";
            case READY_FOR_USER -> "READY_FOR_SUBMISSION";
            case ERROR -> "FAILED";
            case CLOSED -> "CLOSED";
            case NEEDS_MANUAL_APPLY, NEEDS_ANSWERS, SESSION_EXPIRED, EMAIL_ONLY, NO_APPLY_LINK, APPLY_MANUALLY -> "NEEDS_ATTENTION";
        };
    }

    private final long jobId;
    private final String title;
    private final String employer;
    private String url;
    private Status status = Status.OPENING;
    private String message = "Opening the application";
    private List<Done> done = List.of();
    private List<Skipped> skipped = List.of();
    private List<Question> questions = List.of();
    private int passes;
    private String signInHost;
    private String loginFilledUrl;
    private Stage stage = Stage.OPENING_AD;
    private Stage failedStage;
    private String failure;
    private List<String> reasons = List.of();
    private boolean filledAnything;
    private boolean pollForForm;
    private boolean pinned;
    final AtomicBoolean busy = new AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE = new java.util.concurrent.atomic.AtomicLong();
    private final long seq = SEQUENCE.incrementAndGet();

    TabState(long jobId, String title, String employer, String url) {
        this.jobId = jobId;
        this.title = title;
        this.employer = employer;
        this.url = url;
    }

    synchronized void set(Status status, String message) {
        this.status = status;
        this.message = message;
        if (status != Status.ERROR) {
            this.failedStage = null;
            this.failure = null;
        }
    }

    synchronized void stage(Stage stage) {
        this.stage = stage;
    }

    synchronized Stage stage() {
        return stage;
    }

    /** The work stopped in the current stage: remembered so the card can say which step failed and why. */
    synchronized void fail(String why) {
        this.failedStage = stage;
        this.failure = why;
        this.status = Status.ERROR;
        this.message = "Stopped while " + stage.label().toLowerCase(java.util.Locale.ROOT) + ": " + why;
    }

    synchronized void reasons(List<String> reasons) {
        this.reasons = List.copyOf(reasons);
    }

    /** Set once JobPilot has typed or chosen anything on the form; from then on nothing may open another page on its own. */
    synchronized void filledAnything(boolean v) {
        this.filledAnything = v;
    }

    synchronized boolean filledAnything() {
        return filledAnything;
    }

    /** The way to the form could not be found: watch the page, and carry on when the user has opened the form. */
    synchronized void pollForForm(boolean v) {
        this.pollForForm = v;
    }

    synchronized boolean pollForForm() {
        return pollForForm;
    }

    /** The user asked to take over: the window stays on screen until the tab is closed. */
    synchronized void pinned(boolean v) {
        this.pinned = v;
    }

    synchronized boolean pinned() {
        return pinned;
    }

    synchronized void result(List<Done> done, List<Skipped> skipped, List<Question> questions, int passes) {
        this.done = List.copyOf(done);
        this.skipped = List.copyOf(skipped);
        this.questions = List.copyOf(questions);
        this.passes = passes;
    }

    synchronized void removeQuestions(java.util.function.Predicate<Question> match) {
        List<Question> rest = new ArrayList<>(questions);
        rest.removeIf(match);
        questions = rest;
    }

    synchronized void signInHost(String host) {
        this.signInHost = host;
    }

    /** Records that the saved login was typed into the page at this address, so it is not typed again over the user's edits. */
    synchronized void loginFilled(String url) {
        this.loginFilledUrl = url;
    }

    synchronized boolean loginFilledFor(String url) {
        return url != null && url.equals(loginFilledUrl);
    }

    synchronized void url(String url) {
        this.url = url;
    }

    synchronized Status status() {
        return status;
    }

    synchronized List<Question> questions() {
        return questions;
    }

    synchronized String url() {
        return url;
    }

    /** Creation order: queued applications start in the order they were added. */
    long seq() {
        return seq;
    }

    long jobId() {
        return jobId;
    }

    synchronized View view() {
        return view(false);
    }

    synchronized View view(boolean browserVisible) {
        return new View(jobId, title, employer, url, status, message, done, skipped, questions, passes, phaseOf(status), signInHost,
                loginFilledUrl != null, stage.name(), stage.label(), failedStage == null ? null : failedStage.name(), failure, reasons,
                browserVisible);
    }
}
