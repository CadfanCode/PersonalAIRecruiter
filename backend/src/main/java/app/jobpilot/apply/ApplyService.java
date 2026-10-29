package app.jobpilot.apply;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import app.jobpilot.apply.ActionExecutor.Done;
import app.jobpilot.apply.ActionValidator.Skipped;
import app.jobpilot.apply.FormActions.Question;
import app.jobpilot.apply.PageClassifier.Kind;
import app.jobpilot.apply.TabState.Status;
import app.jobpilot.claude.ClaudeResources;
import app.jobpilot.claude.ClaudeCallException;
import app.jobpilot.claude.ClaudeRunner;
import app.jobpilot.credentials.SavedLoginService;
import app.jobpilot.claude.ClaudeTask;
import app.jobpilot.common.ApiExceptions.NotFoundException;
import app.jobpilot.cvdb.AnswerKeys;
import app.jobpilot.cvdb.CvModel.Answer;
import app.jobpilot.cvdb.CvModel.Profile;
import app.jobpilot.cvdb.CvService;
import app.jobpilot.documents.DocumentModel;
import app.jobpilot.documents.DocumentRepository;
import app.jobpilot.manual.PasteOnlyHosts;
import app.jobpilot.jobs.JobModel.Row;
import app.jobpilot.jobs.JobRepository;
import app.jobpilot.ranking.CvDigestService;
import app.jobpilot.settings.JobPilotProperties;

/**
 * Opens one tab per job and fills what it can, stopping before submit (SPEC 9.2). It pauses at logins and BankID,
 * never clicks Ansök or any submit-like control, and sets a job to FILLED but never to APPLIED.
 */
@Service
public class ApplyService {

    private static final Logger log = LoggerFactory.getLogger(ApplyService.class);
    /** Looks at the page at most this many times per fill (an ad, its apply page and the steps of a long form). */
    private static final int MAX_STEPS = 30;
    /** Two looks in a row that changed nothing end the run. */
    private static final int MAX_IDLE = 2;
    private static final int OPEN_ATTEMPTS = 2;

    private final BrowserService browser;
    private final ClaudeRunner claude;
    private final JobRepository jobs;
    private final CvService cv;
    private final CvDigestService digests;
    private final DocumentRepository documents;
    private final SnapshotRepository snapshots;
    private final ExecutorService executor;
    private final PasteOnlyHosts pasteOnly;
    private final SavedLoginService savedLogins;
    private final ObjectMapper mapper;
    private final JobPilotProperties props;
    private final Map<Long, TabState> tabs = new ConcurrentHashMap<>();

    public ApplyService(BrowserService browser, ClaudeRunner claude, JobRepository jobs, CvService cv, CvDigestService digests,
                        DocumentRepository documents, SnapshotRepository snapshots, ExecutorService applyExecutor, PasteOnlyHosts pasteOnly,
                        ObjectMapper mapper, JobPilotProperties props, SavedLoginService savedLogins) {
        this.savedLogins = savedLogins;
        this.browser = browser;
        this.claude = claude;
        this.jobs = jobs;
        this.cv = cv;
        this.digests = digests;
        this.documents = documents;
        this.snapshots = snapshots;
        this.executor = applyExecutor;
        this.pasteOnly = pasteOnly;
        this.mapper = mapper;
        this.props = props;
    }

    // ---- API ----

    public List<TabState.View> open(List<Long> jobIds) {
        if (jobIds != null && jobIds.size() > props.browser().maxTabs()) {
            throw new IllegalArgumentException("At most " + props.browser().maxTabs() + " applications can be opened at once");
        }
        return start(jobIds, false);
    }

    /**
     * Finalize Applications "Run all": every job is accepted, at most {@code maxTabs} tabs are open at once and the rest
     * wait as QUEUED until a tab is closed or its application is marked as submitted.
     */
    public List<TabState.View> openBatch(List<Long> jobIds) {
        return start(jobIds, true);
    }

    private synchronized List<TabState.View> start(List<Long> jobIds, boolean queue) {
        if (jobIds == null || jobIds.isEmpty()) {
            throw new IllegalArgumentException("jobIds is required");
        }
        // Resolve and validate every target first, so a bad third job cannot leave two tabs half started.
        record Plan(long jobId, Row row, Target target) {
        }
        List<Plan> plans = new ArrayList<>();
        for (long jobId : jobIds.stream().distinct().toList()) {
            Row row = jobs.findRow(jobId, null).orElseThrow(() -> new NotFoundException("No job " + jobId));
            plans.add(new Plan(jobId, row, target(jobId, row)));
        }
        List<TabState.View> out = new ArrayList<>();
        for (Plan plan : plans) {
            TabState current = tabs.get(plan.jobId());
            if (current != null && (current.status() == Status.OPENING || current.status() == Status.FILLING
                    || current.status() == Status.QUEUED || current.busy.get())) {
                out.add(viewOf(current)); // a second click must not start a second fill on the same page
                continue;
            }
            TabState state = new TabState(plan.jobId(), plan.row().title(), plan.row().employer(), plan.target().url());
            boolean wait = queue && plan.target().url() != null && activeTabs() >= props.browser().maxTabs();
            tabs.put(plan.jobId(), state);
            if (plan.target().url() == null) {
                if (plan.target().manualSite()) {
                    state.set(Status.APPLY_MANUALLY, "Apply on the site yourself: it discourages automatic use, so JobPilot never opens it in its browser. "
                            + "Use the CV and cover letter PDFs from Documents, then confirm here when you have sent it.");
                } else {
                    state.set(plan.target().email() ? Status.EMAIL_ONLY : Status.NO_APPLY_LINK,
                            plan.target().email() ? "This ad is applied for by email, so there is nothing to open. Use the cover letter and CV PDFs."
                                    : "The ad has no application link. Open the ad itself and apply from there.");
                }
            } else if (wait) {
                state.set(Status.QUEUED, "Waiting for a free browser tab. Close or finish one of the open applications.");
            } else {
                background(state, () -> openAndFill(state));
            }
            out.add(viewOf(state));
        }
        return out;
    }

    /** Tabs that use one of the {@code maxTabs} slots. */
    private int activeTabs() {
        int n = 0;
        for (TabState t : tabs.values()) {
            Status s = t.status();
            if (s != Status.CLOSED && s != Status.EMAIL_ONLY && s != Status.NO_APPLY_LINK && s != Status.APPLY_MANUALLY && s != Status.QUEUED) {
                n++;
            }
        }
        return n;
    }

    private synchronized void startQueued() {
        List<TabState> waiting = tabs.values().stream().filter(t -> t.status() == Status.QUEUED)
                .sorted(java.util.Comparator.comparingLong(TabState::seq)).toList();
        for (TabState state : waiting) {
            if (activeTabs() >= props.browser().maxTabs()) {
                return;
            }
            state.set(Status.OPENING, "Opening the application");
            background(state, () -> openAndFill(state));
        }
    }

    /** Re-reads the page and fills it again: after the user signed in, pressed Next, or answered questions. */
    public TabState.View fill(long jobId, boolean restart) {
        TabState state = state(jobId);
        if (state.url() == null) {
            throw new IllegalArgumentException("This job has no page to fill");
        }
        if (state.busy.get() || state.status() == Status.QUEUED) {
            return viewOf(state);
        }
        state.set(Status.FILLING, restart ? "Restarting the application" : "Reading the page");
        background(state, () -> {
            if (restart) {
                browser.openTab(jobId, state.url());
            }
            fillPage(state);
        });
        return viewOf(state);
    }

    public List<TabState.View> status() {
        List<TabState.View> out = new ArrayList<>();
        tabs.values().stream().sorted(java.util.Comparator.comparingLong(TabState::jobId)).forEach(t -> out.add(viewOf(t)));
        return out;
    }

    public record AnswerInput(String fieldId, String question, String answer) {
    }

    /** Saves the answers in the answers memory (keyed by normalised question) and drops the questions. Fill again to use them. */
    public TabState.View saveAnswers(long jobId, List<AnswerInput> answers) {
        TabState state = state(jobId);
        for (AnswerInput a : answers) {
            String question = a.question() == null || a.question().isBlank()
                    ? state.questions().stream().filter(q -> q.fieldId().equals(a.fieldId())).map(Question::question).findFirst().orElse(null)
                    : a.question();
            if (question == null || a.answer() == null || a.answer().isBlank()) {
                throw new IllegalArgumentException("Each answer needs a question and a non-empty answer");
            }
            cv.saveAnswer(question, a.answer());
            String key = AnswerKeys.normalise(question);
            state.removeQuestions(q -> q.fieldId().equals(a.fieldId()) || AnswerKeys.normalise(q.question()).equals(key));
        }
        return viewOf(state);
    }

    /**
     * A document of this job lost its approval. A form that was already filled had the old file uploaded, so it is sent back to
     * "needs attention" (the user approves again, then fills again); an application still waiting is simply told that the
     * file will not be uploaded until it is approved. Returns what to tell the user, or empty when no application is open.
     */
    public java.util.Optional<String> documentUnapproved(long jobId, String what) {
        TabState state = tabs.get(jobId);
        if (state == null) {
            return java.util.Optional.empty();
        }
        Status now = state.status();
        if ((now == Status.READY_FOR_USER || now == Status.NEEDS_ANSWERS) && !state.busy.get()) {
            String message = "The " + what + " was unapproved after this form was filled. Approve it again, then press Fill this page "
                    + "to upload the current file. Nothing has been submitted.";
            state.set(Status.NEEDS_MANUAL_APPLY, message);
            return java.util.Optional.of("An application for this job is open in the browser. " + message);
        }
        if (now == Status.CLOSED || now == Status.EMAIL_ONLY || now == Status.NO_APPLY_LINK || now == Status.APPLY_MANUALLY) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of("An application for this job is waiting. The " + what + " will not be uploaded until it is approved again.");
    }

    private TabState.View viewOf(TabState state) {
        return state.view(browser.windowShown());
    }

    public void showTab(long jobId) {
        TabState state = state(jobId);
        if (state.status() == Status.QUEUED) {
            throw new app.jobpilot.common.ApiExceptions.ConflictException(
                    "This application is waiting for a free browser tab and has no tab to show yet");
        }
        try {
            state.pinned(true); // the person is taking over: the window stays on screen until the tab is closed
            browser.reveal(jobId);
        } catch (BrowserService.TabClosedException e) {
            markClosed(state);
            throw e;
        }
    }

    /** Idempotent: closing a tab that is already gone, or whose browser died, still ends in CLOSED. */
    public void close(long jobId) {
        TabState state = state(jobId);
        try {
            browser.closeTab(jobId);
        } catch (RuntimeException e) {
            log.info("Closing the tab of job {}: {}", jobId, e.toString());
        }
        state.pinned(false);
        state.set(Status.CLOSED, "The tab was closed. Use Restart and refill to open it again.");
    }

    /** The application was deleted: its tab is closed and nothing about it is remembered. */
    public void discard(long jobId) {
        if (tabs.remove(jobId) == null) {
            return;
        }
        try {
            browser.closeTab(jobId);
        } catch (RuntimeException e) {
            log.info("Closing the tab of deleted job {}: {}", jobId, e.toString());
        }
    }

    /** The user confirmed the submission: the tab has done its job, so its slot is released for the next application. */
    public void finish(long jobId) {
        TabState state = tabs.get(jobId);
        if (state == null) {
            return;
        }
        try {
            browser.closeTab(jobId);
        } catch (RuntimeException e) {
            log.info("Closing the tab of submitted job {}: {}", jobId, e.toString());
        }
        state.pinned(false);
        state.set(Status.CLOSED, "Submitted. The tab was closed.");
    }

    private static void markClosed(TabState state) {
        Status now = state.status();
        if (now != Status.EMAIL_ONLY && now != Status.NO_APPLY_LINK && now != Status.APPLY_MANUALLY) {
            state.set(Status.CLOSED, "The tab was closed. Use Restart and refill to open it again.");
        }
    }

    // ---- the fill flow (runs on the pipeline executor; page access goes through BrowserService) ----

    private void openAndFill(TabState state) {
        if (!state.busy.compareAndSet(false, true)) {
            return;
        }
        try {
            state.stage(TabState.Stage.OPENING_AD);
            state.set(Status.OPENING, "Opening the job ad");
            openWithRetry(state);
        } catch (BrowserService.TabClosedException e) {
            state.set(Status.CLOSED, "The tab was closed. Open the application again to continue.");
            return;
        } catch (RuntimeException e) {
            fail(state, e);
            return;
        } finally {
            state.busy.set(false);
        }
        fillPage(state);
    }

    /** Opening a page is the one step worth repeating blindly: a slow server or a dropped connection often works the second time. */
    private void openWithRetry(TabState state) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= OPEN_ATTEMPTS; attempt++) {
            try {
                browser.openTab(state.jobId(), state.url());
                return;
            } catch (BrowserService.TabClosedException | IllegalArgumentException e) {
                throw e; // a closed tab, or too many tabs: trying again changes nothing
            } catch (RuntimeException e) {
                last = e;
                log.info("Opening job {} failed (attempt {}): {}", state.jobId(), attempt, firstLine(e));
            }
        }
        throw new IllegalStateException("the page did not load (" + firstLine(last) + ")", last);
    }

    /** The step that was running stops here with a clear message; the card names it and offers to open the browser. */
    private void fail(TabState state, Throwable e) {
        log.warn("Application of job {} stopped in stage {}", state.jobId(), state.stage(), e);
        state.fail(firstLine(e));
    }

    private static String firstLine(Throwable e) {
        String m = e == null || e.getMessage() == null ? String.valueOf(e) : e.getMessage();
        int nl = m.indexOf('\n');
        return (nl > 0 ? m.substring(0, nl) : m).strip();
    }

    void fillPage(TabState state) {
        if (!state.busy.compareAndSet(false, true)) {
            return;
        }
        try {
            state.pollForForm(false);
            state.stage(TabState.Stage.FINDING_FORM);
            state.set(Status.FILLING, "Looking at the page");
            browse(state);
        } catch (BrowserService.TabClosedException e) {
            state.set(Status.CLOSED, "The tab was closed. Open the application again to continue.");
        } catch (RuntimeException e) {
            fail(state, e);
        } finally {
            state.busy.set(false);
        }
    }

    private boolean addressAllowed(String url) {
        try {
            String scheme = URI.create(url).getScheme();
            scheme = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
            boolean schemeOk = scheme.equals("http") || scheme.equals("https") || (scheme.equals("file") && props.browser().allowFileUrls());
            return schemeOk && !pasteOnly.matches(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** What one look at the page returned. The snapshot text that goes to the model has no text box contents (see {@link PageSnapshot}). */
    private record Look(PageScripts.PageState page, PageSnapshot snapshot, List<FormField> fileInputs) {
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted");
        }
    }

    /** Brings the window on screen for the user. A failure to move it never stops the application. */
    private void reveal(TabState state) {
        try {
            browser.reveal(state.jobId());
        } catch (RuntimeException e) {
            log.info("Could not show the browser for job {}: {}", state.jobId(), firstLine(e));
        }
    }

    /**
     * Works through the application the way a person with a screen reader would: look at the page (an accessibility snapshot), ask
     * the model what to do on it, let {@link ActionValidator} and {@link BrowserGuard} check and perform that, and look again. The
     * same loop opens the ad's Apply link, fills a step, presses its Next button and repeats, until the page is a review, nothing
     * more can be done, a question needs the person, or a wall needs them to sign in. It never presses anything that sends the
     * application (hard rule 1, ADR 0023), whatever the model says.
     */
    private void browse(TabState state) {
        long jobId = state.jobId();
        Row row = jobs.findRow(jobId, null).orElseThrow();
        Profile profile = cv.profile();
        Map<String, String> memory = new HashMap<>();
        for (Answer a : cv.answers()) {
            memory.put(a.questionKey(), a.answer());
        }
        Map<String, Path> files = approvedFiles(jobId);
        ApplyContext ctx = new ApplyContext(profile, memory, files);
        String digest = digests.build().json();

        List<Done> done = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        Map<String, Question> questions = new LinkedHashMap<>();
        List<String> history = new ArrayList<>();
        long formDeadline = System.nanoTime() + props.browser().findFormSeconds() * 1_000_000_000L;
        boolean sawForm = false;
        boolean pressedAny = false;
        java.util.Set<String> tried = new java.util.HashSet<>(); // page and button already pressed: never pressed twice
        java.util.Set<String> performed = new java.util.HashSet<>(); // page, field and value already done: not repeated, not progress
        int idle = 0;
        PageSnapshot previous = null;
        int step = 0;

        while (step < MAX_STEPS) {
            if (!sawForm && System.nanoTime() > formDeadline) {
                throw new IllegalStateException("no application form was found within " + props.browser().findFormSeconds()
                        + " seconds (still at " + state.url() + ")");
            }
            step++;
            Look look = browser.withTab(jobId, page -> {
                BrowserGuard guard = new BrowserGuard(page, props.browser().suggestionWaitMillis(), null);
                guard.awaitStable();
                guard.dismissCookieBanner();
                return new Look(PageScripts.detect(page), PageScripts.snapshot(page),
                        PageScripts.extract(page).stream().filter(f -> "file".equals(f.type())).toList());
            }, 90);
            PageScripts.PageState ps = look.page();
            state.url(ps.url());
            sawForm |= ps.fieldCount() > 0;
            List<String> reasons = new ArrayList<>(PageClassifier.explain(ps).reasons());
            state.reasons(reasons);
            log.info("Job {}: step {} at {} ({} fields)", jobId, step, ps.url(), ps.fieldCount());

            if (ps.sessionError()) {
                state.result(done, skipped, new ArrayList<>(questions.values()), step);
                state.set(Status.SESSION_EXPIRED, "The session expired or the page failed. Use Restart and refill.");
                return;
            }
            boolean guestRoute = look.snapshot().clickables().stream().anyMatch(c -> GuardPatterns.isAdvance(c.name()) && !c.name().toLowerCase(Locale.ROOT).startsWith("next"));
            if ((ps.passwordVisible() || ps.confirmPasswordText()) && !guestRoute) {
                reasons.add("no way on without signing in");
                finishAtWall(state, done, skipped, questions, step, ps.url());
                return;
            }

            if (previous != null && look.snapshot().sameAs(previous)) {
                idle++; // nothing changed since the last step, whatever it did
            }
            if (idle >= MAX_IDLE) {
                endWithoutProgress(state, done, skipped, questions, step, ps, pressedAny
                        ? "The Apply button was pressed but no form appeared." : "No Apply link or button was found on this page.");
                return;
            }
            previous = look.snapshot();

            FormActions.Step answer = askAgent(row, profile, files, memory, digest, look, history, ps);
            String kind = answer.pageKind() == null ? "other" : answer.pageKind();
            if (answer.summary() != null && !answer.summary().isBlank()) {
                reasons.add(answer.summary().strip());
                state.reasons(reasons);
                history.add(kind + ": " + answer.summary().strip());
            }

            List<FormField> fields = new ArrayList<>(look.snapshot().fields());
            fields.addAll(look.fileInputs());
            List<FormActions.MappedAction> all = answer.actions() == null ? List.of() : answer.actions();
            List<FormActions.MappedAction> fieldActions = all.stream().filter(a -> !"click".equalsIgnoreCase(a.action())).toList();
            FormActions.MappedAction click = all.stream().filter(a -> "click".equalsIgnoreCase(a.action())).findFirst().orElse(null);

            ActionValidator.Validated validated = ActionValidator.validate(fields, new FormActions(fieldActions, answer.questionsForUser()), ctx);
            if (validated.planned().stream().anyMatch(p -> "upload".equals(p.action()))) {
                state.stage(TabState.Stage.UPLOADING);
                state.set(Status.FILLING, "Uploading the approved CV and cover letter");
            } else if (!validated.planned().isEmpty()) {
                state.stage(TabState.Stage.PERSONAL_DETAILS);
                state.set(Status.FILLING, "Filling in the form");
            }
            int doneBefore = done.size();
            String pageKey = ps.url().replaceFirst("#.*$", "");
            List<ActionValidator.Planned> todo = validated.planned().stream()
                    .filter(p -> !performed.contains(pageKey + "|" + p.field().id() + "|" + p.action() + "|" + p.value() + "|" + p.file())).toList();
            if (!todo.isEmpty()) {
                ActionExecutor.Result result = browser.withTab(jobId, page ->
                        new ActionExecutor(page, new BrowserGuard(page, props.browser().suggestionWaitMillis(), null)).execute(todo), 120);
                for (ActionValidator.Planned p : todo) {
                    performed.add(pageKey + "|" + p.field().id() + "|" + p.action() + "|" + p.value() + "|" + p.file());
                }
                if (!result.done().isEmpty()) {
                    state.filledAnything(true); // from now on nothing may open another page on its own
                }
                done.addAll(result.done());
                result.skipped().stream().filter(sk -> !skipped.contains(sk)).forEach(skipped::add);
            }
            validated.skipped().stream().filter(sk -> !"not a field on this page".equals(sk.reason()) && !skipped.contains(sk)).forEach(skipped::add);
            validated.questions().forEach(q -> questions.putIfAbsent(q.fieldId(), q));
            snapshots.save(jobId, ps.url(), fields, Map.of("step", step, "kind", kind, "done", done.size(), "skipped", skipped.size()));
            boolean filled = done.size() > doneBefore;

            if (!questions.isEmpty()) {
                state.result(done, skipped, new ArrayList<>(questions.values()), step);
                state.stage(TabState.Stage.QUESTIONS);
                state.set(Status.NEEDS_ANSWERS, "Filled " + done.size() + " fields. " + questions.size() + " question(s) need your answer.");
                return;
            }
            if (kind.equals("login") && !filled && click == null) {
                if (ps.passwordVisible() || ps.bankIdText() || ps.signInText() || ps.createAccountText()) {
                    reasons.add("no way on without signing in");
                    state.reasons(reasons);
                    finishAtWall(state, done, skipped, questions, step, ps.url());
                    return;
                }
            }
            if (kind.equals("error")) {
                state.result(done, skipped, new ArrayList<>(questions.values()), step);
                state.set(Status.SESSION_EXPIRED, "The page reports an error or an expired session. Use Restart and refill.");
                return;
            }
            if (kind.equals("review") || kind.equals("confirmation")) {
                finishReady(state, done, skipped, questions, step);
                return;
            }

            boolean moved = false;
            if (click != null) {
                PageSnapshot.Node target = look.snapshot().node(click.fieldId());
                String signature = ps.url().replaceFirst("#.*$", "") + "|" + (target == null ? click.fieldId() : target.name());
                if (!tried.add(signature)) {
                    skipped.add(new Skipped(String.valueOf(click.fieldId()), target == null ? "" : target.name(),
                            "Pressing '" + (target == null ? click.fieldId() : target.name()) + "' did not lead to a form"));
                } else {
                    moved = pressOn(state, look.snapshot(), click, skipped);
                    pressedAny |= moved;
                }
            }
            if (filled || moved) {
                idle = 0;
                continue;
            }
            if (click == null && state.filledAnything() && !filled) {
                // The model has nothing left to do here: the last step of the form.
                finishReady(state, done, skipped, questions, step);
                return;
            }
            idle++;
            if (idle >= MAX_IDLE) {
                endWithoutProgress(state, done, skipped, questions, step, ps, pressedAny ? "The Apply button was pressed but no form appeared."
                        : click == null ? "No Apply link or button was found on this page." : "The button could not be pressed.");
                return;
            }
            sleep(2_000); // a page that draws its buttons late gets one more look
        }
        endWithoutProgress(state, done, skipped, questions, step, browser.withTab(jobId, PageScripts::detect), "The application has more than " + MAX_STEPS + " steps.");
    }

    /** Presses the one link or button the model named, through the guard. True when the page moved on. */
    private boolean pressOn(TabState state, PageSnapshot snapshot, FormActions.MappedAction click, List<Skipped> skipped) {
        long jobId = state.jobId();
        String ref = click.fieldId();
        PageSnapshot.Node target = snapshot.node(ref);
        if (target == null || !(target.role().equals("button") || target.role().equals("link") || target.role().equals("menuitem"))) {
            skipped.add(new Skipped(String.valueOf(ref), "", "not a button or link on this page"));
            return false;
        }
        state.set(Status.FILLING, "Pressing '" + target.name() + "'");
        BrowserGuard.Opened opened = browser.withTab(jobId, page -> {
            BrowserGuard.Opened o = new BrowserGuard(page, props.browser().suggestionWaitMillis(), null)
                    .follow(ref, state.filledAnything(), this::addressAllowed);
            if (o.popup() != null) {
                browser.adoptTab(jobId, o.popup());
            }
            return o;
        }, 60);
        log.info("Job {}: '{}' {}", jobId, target.name(), opened.how());
        if (!opened.done()) {
            skipped.add(new Skipped(ref, target.name(), opened.how()));
        }
        return opened.done();
    }

    private void finishAtWall(TabState state, List<Done> done, List<Skipped> skipped, Map<String, Question> questions, int steps, String url) {
        state.result(done, skipped, new ArrayList<>(questions.values()), steps);
        state.set(Status.WAITING_FOR_SIGN_IN, SIGN_IN_MESSAGE);
        offerSavedLogin(state, url);
        reveal(state);
    }

    private void finishReady(TabState state, List<Done> done, List<Skipped> skipped, Map<String, Question> questions, int steps) {
        state.result(done, skipped, new ArrayList<>(questions.values()), steps);
        state.stage(TabState.Stage.READY_FOR_REVIEW);
        state.set(Status.READY_FOR_USER, "Filled " + done.size() + " field(s), " + skipped.size()
                + " left for you. Check the page and submit it yourself.");
        if (!done.isEmpty()) {
            jobs.markFilled(state.jobId());
        }
        reveal(state);
    }

    private void endWithoutProgress(TabState state, List<Done> done, List<Skipped> skipped, Map<String, Question> questions, int steps,
                                    PageScripts.PageState ps, String why) {
        if (state.filledAnything()) {
            finishReady(state, done, skipped, questions, steps);
            return;
        }
        state.result(done, skipped, new ArrayList<>(questions.values()), steps);
        state.pollForForm(true);
        state.set(Status.NEEDS_MANUAL_APPLY, why + " Open the browser, go on to the application form yourself, "
                + "and JobPilot carries on by itself once the form is showing.");
    }

    /** Asks the model what to do on this page; one more try when the call fails, because a second attempt often works. */
    private FormActions.Step askAgent(Row row, Profile profile, Map<String, Path> files, Map<String, String> memory, String digest,
                                      Look look, List<String> history, PageScripts.PageState ps) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("url", ps.url());
        facts.put("title", ps.title());
        facts.put("visiblePasswordField", ps.passwordVisible());
        facts.put("fileInputs", look.fileInputs().stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.id());
            m.put("label", f.questionText());
            m.put("accept", f.accept());
            m.put("multiple", f.multiple());
            return m;
        }).toList());
        ClaudeTask<FormActions.Step> task = ClaudeTask.of("browseApplication", ClaudeResources.prompt("browseApplication", Map.of(
                        "snapshot", look.snapshot().forPrompt(), "page", json(facts), "profile", json(profileForPrompt(profile)),
                        "job", json(Map.of("title", String.valueOf(row.title()), "employer", String.valueOf(row.employer()))),
                        "documents", json(Map.of("cv", Map.of("available", files.containsKey("cv")),
                                "letter", Map.of("available", files.containsKey("letter")))),
                        "memory", json(memory.entrySet().stream().map(e -> Map.of("question", e.getKey(), "answer", e.getValue())).toList()),
                        "cv", digest, "history", json(history))),
                ClaudeResources.schema("browseApplication"), FormActions.Step.class);
        try {
            return claude.run(task);
        } catch (ClaudeCallException first) {
            String why = String.valueOf(first.getMessage()).toLowerCase(Locale.ROOT);
            if (why.contains("did not answer") || why.contains("timed out") || why.contains("timeout")) {
                // A call that already used its whole time limit is not repeated: the stage would hold twice as long.
                throw new IllegalStateException("the AI took too long to work out the form: " + firstLine(first), first);
            }
            log.info("The AI did not answer for the page ({}); trying once more", firstLine(first));
            try {
                return claude.run(task);
            } catch (ClaudeCallException second) {
                throw new IllegalStateException("the AI could not work out the form: " + firstLine(second), second);
            }
        }
    }

    private static Map<String, Object> profileForPrompt(Profile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fullName", p.fullName());
        m.put("firstName", p.fullName() == null ? null : p.fullName().split("\\s+", 2)[0]);
        m.put("lastName", p.fullName() == null || !p.fullName().contains(" ") ? null : p.fullName().split("\\s+", 2)[1]);
        m.put("email", p.email());
        m.put("phone", p.phone());
        m.put("phoneInternational", p.phoneInternational());
        m.put("city", p.city());
        m.put("website", p.website());
        m.put("github", p.github());
        m.put("linkedin", p.linkedin());
        return m;
    }

    /** Only documents the user approved; a missing or unapproved document is simply not offered. */
    private Map<String, Path> approvedFiles(long jobId) {
        Map<String, Path> files = new HashMap<>();
        for (DocumentModel.Kind kind : DocumentModel.Kind.values()) {
            documents.find(jobId, kind).filter(DocumentRepository.Row::approved).ifPresent(d -> {
                Path p = d.pdfPath() == null ? null : Path.of(d.pdfPath());
                if (p != null && Files.isRegularFile(p)) {
                    files.put(kind == DocumentModel.Kind.CV ? "cv" : "letter", p);
                }
            });
        }
        return files;
    }

    // ---- waiting for sign-in ----

    /** Read-only poll every 3 s (SPEC 9.2 step 2): when the wall is gone, fill the page. */
    @Scheduled(fixedDelayString = "${jobpilot.browser.poll-millis:3000}")
    void pollWaitingTabs() {
        reconcileClosedTabs();
        startQueued();
        watchForOpenedForms();
        hideWhenNobodyIsNeeded();
        for (TabState state : tabs.values()) {
            if (state.status() != Status.WAITING_FOR_SIGN_IN || state.busy.get()) {
                continue;
            }
            try {
                PageScripts.PageState ps = browser.withTab(state.jobId(), page -> PageScripts.detect(page));
                if (PageClassifier.classify(ps) != Kind.WAITING_FOR_SIGN_IN) {
                    state.set(Status.FILLING, "Signed in: reading the page");
                    background(state, () -> fillPage(state));
                } else {
                    offerSavedLogin(state, ps.url());
                }
            } catch (BrowserService.TabClosedException e) {
                state.set(Status.CLOSED, "The tab was closed");
            } catch (RuntimeException e) {
                log.debug("Polling job {}: {}", state.jobId(), e.toString());
            }
        }
    }

    /** A tab the navigator could not get past: when the user has opened the form themselves, carry on by itself. */
    private void watchForOpenedForms() {
        for (TabState state : tabs.values()) {
            if (state.status() != Status.NEEDS_MANUAL_APPLY || !state.pollForForm() || state.busy.get()) {
                continue;
            }
            try {
                PageScripts.PageState ps = browser.withTab(state.jobId(), page -> PageScripts.detect(page), 30);
                if (PageClassifier.classify(ps) == Kind.FORM) {
                    state.set(Status.FILLING, "The form is open: filling it");
                    background(state, () -> fillPage(state));
                }
            } catch (BrowserService.TabClosedException e) {
                state.set(Status.CLOSED, "The tab was closed");
            } catch (RuntimeException e) {
                log.debug("Watching job {}: {}", state.jobId(), e.toString());
            }
        }
    }

    /** The window goes back off-screen once no application waits for the user and nobody took over. */
    private void hideWhenNobodyIsNeeded() {
        if (!browser.windowShown() || !browser.backgroundMode()) {
            return;
        }
        for (TabState state : tabs.values()) {
            Status s = state.status();
            boolean needsPerson = s == Status.WAITING_FOR_SIGN_IN || s == Status.READY_FOR_USER || state.pinned();
            if (needsPerson && s != Status.CLOSED) {
                return;
            }
        }
        browser.hide();
    }

    private static final String SIGN_IN_MESSAGE = "Waiting for you to sign in. JobPilot never creates accounts or handles BankID.";

    /**
     * Notes which portal the tab is waiting at (so the card can offer to save a login) and, when the user saved a login for
     * exactly that host, types it into the login form once. It never presses the login button: signing in is the user's step.
     * The password goes from the credential store straight to the page and into no log, prompt or status text.
     */
    private void offerSavedLogin(TabState state, String url) {
        String host = hostOf(url);
        state.signInHost(host);
        if (host == null || state.loginFilledFor(url)) {
            return;
        }
        try {
            savedLogins.credentialsFor(host).ifPresent(c -> {
                BrowserGuard.LoginResult result = browser.fillSavedLogin(state.jobId(), host, c.username(), c.password());
                if (result == BrowserGuard.LoginResult.FILLED) {
                    state.loginFilled(url);
                    savedLogins.markUsed(host);
                    state.set(Status.WAITING_FOR_SIGN_IN, "Your saved login for " + host
                            + " is filled in. Press Log in yourself; JobPilot carries on once you are in.");
                }
            });
        } catch (BrowserService.TabClosedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.info("The saved login for {} was not used ({})", host, e.getClass().getSimpleName());
        }
    }

    private static String hostOf(String url) {
        try {
            return url == null ? null : java.net.URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A tab the user closed by hand must not keep offering Focus and Close: mark it CLOSED. */
    private void reconcileClosedTabs() {
        if (browser.openTabCount().isEmpty()) {
            return; // the browser never started, so there is nothing to compare with
        }
        for (TabState state : tabs.values()) {
            Status s = state.status();
            boolean hasPage = s != Status.QUEUED && s != Status.FILLING && s != Status.CLOSED && s != Status.EMAIL_ONLY
                    && s != Status.NO_APPLY_LINK && s != Status.APPLY_MANUALLY && s != Status.OPENING;
            if (!hasPage || state.busy.get()) {
                continue;
            }
            try {
                if (!browser.hasTab(state.jobId())) {
                    markClosed(state);
                }
            } catch (RuntimeException e) {
                log.debug("Checking the tab of job {}: {}", state.jobId(), e.toString());
            }
        }
    }

    // ---- helpers ----

    private record Target(String url, boolean email, boolean manualSite) {
    }

    /** The apply link, else the ad page. Only http(s) is opened (file: only for test fixtures). */
    private Target target(long jobId, Row row) {
        // A model reading an ad can put just a host name ("jobb.example.se") where an address belongs; that is not a link to open.
        String url = isAddress(row.applyUrl()) ? row.applyUrl() : row.url();
        boolean email = false;
        try {
            JsonNode raw = mapper.readTree(jobs.rawJson(jobId).orElse("{}"));
            email = raw.path("application_details").path("email").asText("").length() > 0;
        } catch (JsonProcessingException ignored) {
            // no raw JSON to read
        }
        if (url == null || url.isBlank()) {
            return new Target(null, email, false);
        }
        if (pasteOnly.matches(url)) {
            return new Target(null, false, true); // never opened in the automated browser
        }
        String scheme = URI.create(url.strip()).getScheme();
        scheme = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https") || (scheme.equals("file") && props.browser().allowFileUrls()))) {
            throw new IllegalArgumentException("Refusing to open a " + (scheme.isEmpty() ? "relative" : scheme) + ": address");
        }
        return new Target(url.strip(), email, false);
    }

    private static boolean isAddress(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            String scheme = URI.create(url.strip()).getScheme();
            return scheme != null && !scheme.isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private TabState state(long jobId) {
        TabState s = tabs.get(jobId);
        if (s == null) {
            throw new NotFoundException("No application is open for job " + jobId);
        }
        return s;
    }

    private void background(TabState state, Runnable task) {
        try {
            executor.submit(() -> {
                try {
                    task.run();
                } catch (BrowserService.TabClosedException e) {
                    state.set(Status.CLOSED, "The tab was closed. Open the application again to continue.");
                } catch (RuntimeException e) {
                    log.warn("Application {} failed", state.jobId(), e);
                    state.set(Status.ERROR, e.getMessage() == null ? e.toString() : e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            state.set(Status.ERROR, "Too many tasks in progress; try again shortly");
        }
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).collect(Collectors.toList());
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
