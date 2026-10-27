package se.caibirch.jobpilot.apply;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import se.caibirch.jobpilot.apply.ActionExecutor.Done;
import se.caibirch.jobpilot.apply.ActionValidator.Skipped;
import se.caibirch.jobpilot.apply.FormActions.Question;

/** What the monitor shows for one application tab. Mutated by the fill thread, read by the status endpoint. */
public final class TabState {

    public enum Status {
        QUEUED, OPENING, FILLING, WAITING_FOR_SIGN_IN, NEEDS_MANUAL_APPLY, NEEDS_ANSWERS, READY_FOR_USER, SESSION_EXPIRED,
        EMAIL_ONLY, NO_APPLY_LINK, CLOSED, ERROR
    }

    /** The JSON the UI gets. */
    public record View(long jobId, String title, String employer, String url, Status status, String message,
                       List<Done> done, List<Skipped> skipped, List<Question> questions, int passes) {
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
        return new View(jobId, title, employer, url, status, message, done, skipped, questions, passes);
    }
}
