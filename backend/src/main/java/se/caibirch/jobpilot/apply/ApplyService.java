package se.caibirch.jobpilot.apply;

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

import se.caibirch.jobpilot.apply.ActionExecutor.Done;
import se.caibirch.jobpilot.apply.ActionValidator.Skipped;
import se.caibirch.jobpilot.apply.FormActions.Question;
import se.caibirch.jobpilot.apply.PageClassifier.Kind;
import se.caibirch.jobpilot.apply.TabState.Status;
import se.caibirch.jobpilot.claude.ClaudeResources;
import se.caibirch.jobpilot.claude.ClaudeRunner;
import se.caibirch.jobpilot.claude.ClaudeTask;
import se.caibirch.jobpilot.common.ApiExceptions.NotFoundException;
import se.caibirch.jobpilot.cvdb.AnswerKeys;
import se.caibirch.jobpilot.cvdb.CvModel.Answer;
import se.caibirch.jobpilot.cvdb.CvModel.Profile;
import se.caibirch.jobpilot.cvdb.CvService;
import se.caibirch.jobpilot.documents.DocumentModel;
import se.caibirch.jobpilot.documents.DocumentRepository;
import se.caibirch.jobpilot.jobs.JobModel.Row;
import se.caibirch.jobpilot.jobs.JobRepository;
import se.caibirch.jobpilot.ranking.CvDigestService;
import se.caibirch.jobpilot.settings.JobPilotProperties;

/**
 * Opens one tab per job and fills what it can, stopping before submit (SPEC 9.2). It pauses at logins and BankID,
 * never clicks Ansök or any submit-like control, and sets a job to FILLED but never to APPLIED.
 */
@Service
public class ApplyService {

    private static final Logger log = LoggerFactory.getLogger(ApplyService.class);
    private static final int MAX_PASSES = 2;

    private final BrowserService browser;
    private final ClaudeRunner claude;
    private final JobRepository jobs;
    private final CvService cv;
    private final CvDigestService digests;
    private final DocumentRepository documents;
    private final SnapshotRepository snapshots;
    private final ExecutorService executor;
    private final ObjectMapper mapper;
    private final JobPilotProperties props;
    private final Map<Long, TabState> tabs = new ConcurrentHashMap<>();

    public ApplyService(BrowserService browser, ClaudeRunner claude, JobRepository jobs, CvService cv, CvDigestService digests,
                        DocumentRepository documents, SnapshotRepository snapshots, ExecutorService applyExecutor,
                        ObjectMapper mapper, JobPilotProperties props) {
        this.browser = browser;
        this.claude = claude;
        this.jobs = jobs;
        this.cv = cv;
        this.digests = digests;
        this.documents = documents;
        this.snapshots = snapshots;
        this.executor = applyExecutor;
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
                out.add(current.view()); // a second click must not start a second fill on the same page
                continue;
            }
            TabState state = new TabState(plan.jobId(), plan.row().title(), plan.row().employer(), plan.target().url());
            boolean wait = queue && plan.target().url() != null && activeTabs() >= props.browser().maxTabs();
            tabs.put(plan.jobId(), state);
            if (plan.target().url() == null) {
                state.set(plan.target().email() ? Status.EMAIL_ONLY : Status.NO_APPLY_LINK,
                        plan.target().email() ? "This ad is applied for by email, so there is nothing to open. Use the cover letter and CV PDFs."
                                : "The ad has no application link. Open the ad itself and apply from there.");
            } else if (wait) {
                state.set(Status.QUEUED, "Waiting for a free browser tab. Close or finish one of the open applications.");
            } else {
                background(state, () -> openAndFill(state));
            }
            out.add(state.view());
        }
        return out;
    }

    /** Tabs that use one of the {@code maxTabs} slots. */
    private int activeTabs() {
        int n = 0;
        for (TabState t : tabs.values()) {
            Status s = t.status();
            if (s != Status.CLOSED && s != Status.EMAIL_ONLY && s != Status.NO_APPLY_LINK && s != Status.QUEUED) {
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
            return state.view();
        }
        state.set(Status.FILLING, restart ? "Restarting the application" : "Reading the page");
        background(state, () -> {
            if (restart) {
                browser.openTab(jobId, state.url());
            }
            fillPage(state);
        });
        return state.view();
    }

    public List<TabState.View> status() {
        List<TabState.View> out = new ArrayList<>();
        tabs.values().stream().sorted(java.util.Comparator.comparingLong(TabState::jobId)).forEach(t -> out.add(t.view()));
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
        return state.view();
    }

    public void showTab(long jobId) {
        TabState state = state(jobId);
        if (state.status() == Status.QUEUED) {
            throw new se.caibirch.jobpilot.common.ApiExceptions.ConflictException(
                    "This application is waiting for a free browser tab and has no tab to show yet");
        }
        try {
            browser.bringToFront(jobId);
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
        state.set(Status.CLOSED, "The tab was closed. Use Restart and refill to open it again.");
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
        state.set(Status.CLOSED, "Submitted. The tab was closed.");
    }

    private static void markClosed(TabState state) {
        Status now = state.status();
        if (now != Status.EMAIL_ONLY && now != Status.NO_APPLY_LINK) {
            state.set(Status.CLOSED, "The tab was closed. Use Restart and refill to open it again.");
        }
    }

    // ---- the fill flow (runs on the pipeline executor; page access goes through BrowserService) ----

    private void openAndFill(TabState state) {
        browser.openTab(state.jobId(), state.url());
        fillPage(state);
    }

    void fillPage(TabState state) {
        long jobId = state.jobId();
        if (!state.busy.compareAndSet(false, true)) {
            return;
        }
        try {
            state.set(Status.FILLING, "Reading the page");
            Snapshot snap = browser.withTab(jobId, page -> {
                new BrowserGuard(page, props.browser().suggestionWaitMillis(), null).dismissCookieBanner();
                PageScripts.PageState ps = PageScripts.detect(page);
                Kind kind = PageClassifier.classify(ps);
                return new Snapshot(kind, kind == Kind.FORM ? PageScripts.extract(page) : List.of(), ps.url());
            });
            state.url(snap.url());
            switch (snap.kind()) {
                case WAITING_FOR_SIGN_IN -> {
                    state.set(Status.WAITING_FOR_SIGN_IN, "Waiting for you to sign in. JobPilot never types passwords or handles BankID.");
                    return;
                }
                case MANUAL_APPLY -> {
                    state.set(Status.NEEDS_MANUAL_APPLY, "Click Ansök yourself in the browser, then press Fill this page.");
                    return;
                }
                case SESSION_ERROR -> {
                    state.set(Status.SESSION_EXPIRED, "The session expired or the page failed. Use Restart and refill.");
                    return;
                }
                case EMPTY -> {
                    state.set(Status.NEEDS_MANUAL_APPLY, "No form was found on this page. Open the application form yourself, then press Fill this page.");
                    return;
                }
                case FORM -> { }
            }
            runPasses(state, snap);
        } catch (BrowserService.TabClosedException e) {
            state.set(Status.CLOSED, "The tab was closed. Open the application again to continue.");
        } catch (RuntimeException e) {
            log.warn("Filling job {} failed", jobId, e);
            state.set(Status.ERROR, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            state.busy.set(false);
        }
    }

    private record Snapshot(Kind kind, List<FormField> fields, String url) {
    }

    private void runPasses(TabState state, Snapshot first) {
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
        List<FormField> fields = first.fields();
        int pass = 0;
        while (!fields.isEmpty() && pass < MAX_PASSES) {
            if (pass > 0) {
                // An upload can bring up a login or account wall; look again before mapping anything more.
                Kind kind = browser.withTab(jobId, page -> PageClassifier.classify(PageScripts.detect(page)));
                if (kind == Kind.WAITING_FOR_SIGN_IN) {
                    state.result(done, skipped, new ArrayList<>(questions.values()), pass);
                    state.set(Status.WAITING_FOR_SIGN_IN, "A sign-in or account page appeared. Waiting for you; JobPilot never types passwords or handles BankID.");
                    return;
                }
            }
            pass++;
            state.set(Status.FILLING, pass == 1 ? "Working out what to fill" : "Filling the fields that appeared after the upload");
            final List<FormField> current = fields;
            FormActions mapped = claude.run(ClaudeTask.of("mapFormFields", ClaudeResources.prompt("mapFormFields", Map.of(
                            "fields", json(promptFields(current)), "profile", json(profileForPrompt(profile)),
                            "job", json(Map.of("title", String.valueOf(row.title()), "employer", String.valueOf(row.employer()))),
                            "documents", json(Map.of("cv", Map.of("available", files.containsKey("cv")),
                                    "letter", Map.of("available", files.containsKey("letter")))),
                            "memory", json(memory.entrySet().stream().map(e -> Map.of("question", e.getKey(), "answer", e.getValue())).toList()),
                            "cv", digest)),
                    ClaudeResources.schema("mapFormFields"), FormActions.class));
            ActionValidator.Validated validated = ActionValidator.validate(current, mapped, ctx);
            ActionExecutor.Result result = browser.withTab(jobId, page ->
                    new ActionExecutor(page, new BrowserGuard(page, props.browser().suggestionWaitMillis(), null)).execute(validated.planned(), current));
            snapshots.save(jobId, state.url(), current, Map.of("pass", pass, "proposed", mapped, "done", result.done(),
                    "skipped", concat(validated.skipped(), result.skipped())));
            done.addAll(result.done());
            skipped.addAll(validated.skipped());
            skipped.addAll(result.skipped());
            validated.questions().forEach(q -> questions.putIfAbsent(q.fieldId(), q));
            fields = result.newFields(); // only fields that appeared during this pass get a second pass
        }

        state.result(done, skipped, new ArrayList<>(questions.values()), pass);
        if (!questions.isEmpty()) {
            state.set(Status.NEEDS_ANSWERS, "Filled " + done.size() + " fields. " + questions.size() + " question(s) need your answer.");
        } else {
            state.set(Status.READY_FOR_USER, "Filled " + done.size() + " field(s), " + skipped.size()
                    + " left for you. Check the page and submit it yourself.");
            if (!done.isEmpty()) {
                jobs.markFilled(jobId);
            }
        }
    }

    /** Fields as Claude sees them: no password, login or credential fields at all. */
    private static List<Map<String, Object>> promptFields(List<FormField> fields) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FormField f : fields) {
            if ("password".equals(f.type()) || GuardPatterns.CREDENTIAL.matcher(f.ownText()).find()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.id());
            m.put("type", f.type());
            m.put("tag", f.tag());
            m.put("label", f.label());
            m.put("ariaLabel", f.ariaLabel());
            m.put("placeholder", f.placeholder());
            m.put("question", f.legend());
            m.put("nearbyText", f.nearbyText());
            m.put("option", f.optionLabel());
            m.put("required", f.required());
            m.put("currentValue", f.value());
            m.put("checked", f.checked());
            m.put("accept", f.accept());
            m.put("multiple", f.multiple());
            m.put("options", f.options() == null ? List.of() : f.options().stream().map(FormField.Option::label).toList());
            out.add(m);
        }
        return out;
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
        for (TabState state : tabs.values()) {
            if (state.status() != Status.WAITING_FOR_SIGN_IN || state.busy.get()) {
                continue;
            }
            try {
                Kind kind = browser.withTab(state.jobId(), page -> PageClassifier.classify(PageScripts.detect(page)));
                if (kind != Kind.WAITING_FOR_SIGN_IN) {
                    state.set(Status.FILLING, "Signed in: reading the page");
                    background(state, () -> fillPage(state));
                }
            } catch (BrowserService.TabClosedException e) {
                state.set(Status.CLOSED, "The tab was closed");
            } catch (RuntimeException e) {
                log.debug("Polling job {}: {}", state.jobId(), e.toString());
            }
        }
    }

    /** A tab the user closed by hand must not keep offering Focus and Close: mark it CLOSED. */
    private void reconcileClosedTabs() {
        if (browser.openTabCount().isEmpty()) {
            return; // the browser never started, so there is nothing to compare with
        }
        for (TabState state : tabs.values()) {
            Status s = state.status();
            boolean hasPage = s != Status.QUEUED && s != Status.FILLING && s != Status.CLOSED && s != Status.EMAIL_ONLY && s != Status.NO_APPLY_LINK && s != Status.OPENING;
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

    private record Target(String url, boolean email) {
    }

    /** The apply link, else the ad page. Only http(s) is opened (file: only for test fixtures). */
    private Target target(long jobId, Row row) {
        String url = row.applyUrl() != null ? row.applyUrl() : row.url();
        boolean email = false;
        try {
            JsonNode raw = mapper.readTree(jobs.rawJson(jobId).orElse("{}"));
            email = raw.path("application_details").path("email").asText("").length() > 0;
        } catch (JsonProcessingException ignored) {
            // no raw JSON to read
        }
        if (url == null || url.isBlank()) {
            return new Target(null, email);
        }
        String scheme = URI.create(url.strip()).getScheme();
        scheme = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https") || (scheme.equals("file") && props.browser().allowFileUrls()))) {
            throw new IllegalArgumentException("Refusing to open a " + (scheme.isEmpty() ? "relative" : scheme) + ": address");
        }
        return new Target(url.strip(), email);
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
