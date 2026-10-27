package se.caibirch.jobpilot.aktivitet;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import se.caibirch.jobpilot.aktivitet.AktivitetForm.FormDiffers;
import se.caibirch.jobpilot.aktivitet.AktivitetModel.Entry;
import se.caibirch.jobpilot.aktivitet.AktivitetModel.Plan;
import se.caibirch.jobpilot.aktivitet.AktivitetModel.Result;
import se.caibirch.jobpilot.apply.BrowserGuard;
import se.caibirch.jobpilot.apply.BrowserGuard.Outcome;
import se.caibirch.jobpilot.apply.BrowserService;
import se.caibirch.jobpilot.apply.FormField;
import se.caibirch.jobpilot.apply.PageClassifier;
import se.caibirch.jobpilot.apply.PageScripts;
import se.caibirch.jobpilot.apply.PageScripts.SavedActivities;
import se.caibirch.jobpilot.common.ApiExceptions.NotFoundException;
import se.caibirch.jobpilot.jobs.JobModel.Row;
import se.caibirch.jobpilot.jobs.JobModel.Status;
import se.caibirch.jobpilot.jobs.JobRepository;
import se.caibirch.jobpilot.pipeline.PipelineModel;
import se.caibirch.jobpilot.pipeline.RunRepository;
import se.caibirch.jobpilot.search.SearchProfileRepository;
import se.caibirch.jobpilot.settings.JobPilotProperties;

/**
 * Saves applied jobs as "Sökta jobb" in Arbetsförmedlingen's aktivitetsrapport (SPEC 10). It writes to a government record,
 * so it is built to fail safe: a job is saved only when all seven values were filled and read back correctly; no confirmation
 * toast stops the whole batch; Spara is never clicked twice; the monthly report is never submitted ("Skicka in" is refused by
 * the guard). It uses no model: the form is fixed and mapped in Java.
 */
@Service
public class AktivitetService {

    private static final Logger log = LoggerFactory.getLogger(AktivitetService.class);
    /** The browser tab of this automation; real job tabs use job ids, which are positive. */
    static final long TAB = -1L;
    static final String TOAST = "Din aktivitet är sparad";
    private static final Set<String> OMFATTNING = Set.of("Heltid", "Deltid", "Timmar vid behov");

    private final BrowserService browser;
    private final JobRepository jobs;
    private final RunRepository runs;
    private final SearchProfileRepository profiles;
    private final AktivitetLogRepository logs;
    private final ExecutorService executor;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final JobPilotProperties props;
    private final long pollMillis;
    private final int toastWaitMs;

    public AktivitetService(BrowserService browser, JobRepository jobs, RunRepository runs, SearchProfileRepository profiles,
                            AktivitetLogRepository logs, ExecutorService pipelineExecutor, ObjectMapper mapper, Clock clock,
                            JobPilotProperties props, @Value("${jobpilot.browser.poll-millis:3000}") long pollMillis,
                            @Value("${jobpilot.aktivitet.toast-wait-millis:8000}") int toastWaitMs) {
        this.browser = browser;
        this.jobs = jobs;
        this.runs = runs;
        this.profiles = profiles;
        this.logs = logs;
        this.executor = pipelineExecutor;
        this.mapper = mapper;
        this.clock = clock;
        this.props = props;
        this.pollMillis = pollMillis;
        this.toastWaitMs = toastWaitMs;
    }

    // ---- planning: what would be written ----

    public List<Plan> plan(List<Long> jobIds) {
        List<Plan> out = new ArrayList<>();
        for (long id : jobIds.stream().distinct().toList()) {
            Row row = jobs.findRow(id, null).orElseThrow(() -> new NotFoundException("No job " + id));
            out.add(plan(row));
        }
        return out;
    }

    Plan plan(Row row) {
        if (row.status() == Status.LOGGED || logs.hasSaved(row.id())) {
            return new Plan(row.id(), row.title(), null, "Already logged in the aktivitetsrapport.");
        }
        if (row.status() != Status.APPLIED) {
            return new Plan(row.id(), row.title(), null, "Mark the job as applied first; only jobs you applied for are logged.");
        }
        if (row.municipality() == null || row.municipality().isBlank()) {
            return new Plan(row.id(), row.title(), null, "The ad has no municipality; log this one by hand (choose Obestämd ort).");
        }
        if (isAbroad(row.id())) {
            return new Plan(row.id(), row.title(), null, "The job is outside Sweden; log this one by hand (choose Utomlands).");
        }
        String yrkesroll = jobs.latestSearchProfileId(row.id()).flatMap(profiles::find)
                .map(p -> p.profile().yrkesrollForAktivitetsrapport()).orElse(props.search().defaultYrkesroll());
        LocalDate date = (row.appliedAt() == null ? clock.instant() : row.appliedAt()).atZone(ZoneId.of("Europe/Stockholm")).toLocalDate();
        return new Plan(row.id(), row.title(), new Entry(row.id(), row.title(), yrkesroll, employerLabel(row), row.municipality(),
                omfattning(row.employmentType()), "Sverige", true, date), null);
    }

    /** "Comstream AB (Taxi Stockholm)" when the ad names an end client, else just the employer (SPEC 10 step 3). */
    static String employerLabel(Row row) {
        String employer = row.employer() == null ? "" : row.employer().strip();
        return row.clientEmployer() == null || row.clientEmployer().isBlank() ? employer : employer + " (" + row.clientEmployer().strip() + ")";
    }

    static String omfattning(String employmentType) {
        String t = employmentType == null ? "" : employmentType.toLowerCase(Locale.ROOT);
        return t.contains("behov") ? "Timmar vid behov" : t.contains("deltid") ? "Deltid" : "Heltid";
    }

    private boolean isAbroad(long jobId) {
        try {
            JsonNode country = mapper.readTree(jobs.rawJson(jobId).orElse("{}")).path("workplace_address").path("country");
            return !country.isMissingNode() && !country.isNull() && !country.asText("Sverige").isBlank()
                    && !country.asText().equalsIgnoreCase("Sverige");
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    // ---- starting a run with what the user confirmed ----

    public long start(List<Entry> confirmed) {
        if (confirmed == null || confirmed.isEmpty()) {
            throw new IllegalArgumentException("Nothing to log");
        }
        LocalDate today = LocalDate.now(clock);
        for (Entry e : confirmed) {
            Row row = jobs.findRow(e.jobId(), null).orElseThrow(() -> new NotFoundException("No job " + e.jobId()));
            Plan plan = plan(row);
            if (plan.entry() == null) {
                throw new IllegalArgumentException("Job " + e.jobId() + ": " + plan.ineligible());
            }
            validate(e, today);
        }
        long runId = runs.createAktivitetRun(clock.instant());
        try {
            executor.submit(() -> guarded(runId, confirmed));
        } catch (RejectedExecutionException ex) {
            runs.finish(runId, PipelineModel.Status.FAILED, "Too many runs in progress; try again shortly", clock.instant());
            throw ex;
        }
        return runId;
    }

    private static void validate(Entry e, LocalDate today) {
        if (blank(e.yrkesroll()) || blank(e.employerLabel()) || blank(e.municipality())) {
            throw new IllegalArgumentException("Job " + e.jobId() + ": yrkesroll, employer and municipality are required");
        }
        if (!OMFATTNING.contains(e.omfattning())) {
            throw new IllegalArgumentException("Job " + e.jobId() + ": omfattning must be Heltid, Deltid or Timmar vid behov");
        }
        if (!"Sverige".equals(e.varFinns())) {
            throw new IllegalArgumentException("Job " + e.jobId() + ": only jobs in Sweden are logged automatically");
        }
        if (e.appliedDate() == null || e.appliedDate().isAfter(today)) {
            throw new IllegalArgumentException("Job " + e.jobId() + ": the application date cannot be in the future");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ---- the run (on the pipeline executor; page access through BrowserService) ----

    private void guarded(long runId, List<Entry> entries) {
        try {
            execute(runId, entries);
        } catch (RuntimeException e) {
            log.error("Aktivitet run {} failed", runId, e);
            runs.finish(runId, PipelineModel.Status.FAILED, e.getMessage() == null ? e.toString() : e.getMessage(), clock.instant());
        } finally {
            try {
                browser.closeTab(TAB);
            } catch (RuntimeException ignored) {
                // the browser is gone already
            }
        }
    }

    void execute(long runId, List<Entry> entries) {
        String listUrl = props.aktivitet().listUrl();
        String formUrl = props.aktivitet().formUrl();
        runs.update(runId, PipelineModel.Status.RUNNING, "OPEN", 5, "Opening the aktivitetsrapport");
        browser.openTab(TAB, listUrl);
        if (!waitForSignIn(runId)) {
            stop(runId, entries, 0, "Gave up waiting for you to sign in. Nothing was saved.");
            return;
        }

        runs.update(runId, PipelineModel.Status.RUNNING, "CHECK", 10, "Reading the saved activities to avoid duplicates");
        SavedActivities saved = browser.withTab(TAB, PageScripts::savedActivities);
        if (saved.entries().isEmpty() && !saved.noneMarker()) {
            stop(runId, entries, 0, "Could not read the list of saved activities, so duplicates cannot be ruled out. "
                    + "Nothing was saved; log these by hand.");
            return;
        }

        int done = 0;
        int savedCount = 0;
        for (Entry e : entries) {
            runs.update(runId, PipelineModel.Status.RUNNING, "SAVE", 15 + 80 * done / entries.size(),
                    "Logging " + (done + 1) + " of " + entries.size() + ": " + e.employerLabel());
            if (logs.hasSaved(e.jobId())) {
                logs.insert(runId, e, Result.DUPLICATE, "Already logged earlier by JobPilot", null);
                jobs.markLogged(e.jobId());
            } else if (isDuplicate(saved, e)) {
                logs.insert(runId, e, Result.DUPLICATE, "The aktivitetsrapport already lists this employer on this date", null);
                jobs.markLogged(e.jobId());
            } else {
                Result result;
                try {
                    result = saveOne(runId, e, formUrl);
                } catch (FormDiffers ex) {
                    logs.insert(runId, e, Result.FAILED, ex.getMessage(), null);
                    stop(runId, entries, done + 1, ex.getMessage());
                    return;
                }
                if (result == Result.UNCONFIRMED) {
                    stop(runId, entries, done + 1, "Stopped after " + e.employerLabel() + ": the confirmation \"" + TOAST
                            + "\" did not appear. It may or may not have been saved: check the aktivitetsrapport by hand before logging more.");
                    return;
                }
                if (result == Result.SAVED) {
                    savedCount++;
                }
            }
            done++;
        }
        runs.finish(runId, PipelineModel.Status.DONE, "Saved " + savedCount + " of " + entries.size() + " in the aktivitetsrapport. "
                + "The monthly report itself is not submitted; do that yourself.", clock.instant());
    }

    /** Fills, verifies and saves one job. Returns FAILED when nothing was saved, UNCONFIRMED when Spara was clicked without a toast. */
    private Result saveOne(long runId, Entry e, String formUrl) {
        browser.openTab(TAB, formUrl); // a fresh form every time, whatever state the last one was left in
        if (!waitForSignIn(runId)) {
            logs.insert(runId, e, Result.FAILED, "Signed out while waiting; not saved", null);
            return Result.FAILED;
        }
        return browser.withTab(TAB, page -> {
            BrowserGuard guard = new BrowserGuard(page, props.browser().suggestionWaitMillis(), formUrl);
            AktivitetForm.Located form = AktivitetForm.locate(PageScripts.extract(page));
            List<String> problems = new ArrayList<>();
            problem(problems, "Yrkesroll", guard.typeAndPick(form.yrkesroll(), prefix(e.yrkesroll(), 21), e.yrkesroll(), false));
            problem(problems, "Arbetsgivaren", guard.fill(form.arbetsgivare(), e.employerLabel()));
            problem(problems, "Omfattning", guard.check(AktivitetForm.option(form.omfattning(), e.omfattning(), "omfattning"), true));
            problem(problems, "Var finns jobbet", guard.check(AktivitetForm.option(form.varFinns(), e.varFinns(), "var finns jobbet"), true));
            problem(problems, "Ort", guard.typeAndPick(form.ort(), prefix(e.municipality(), 6), e.municipality(), true));
            problem(problems, "Svarade du på en annons", guard.check(AktivitetForm.option(form.annons(), e.answeredAd() ? "ja" : "nej", "annons"), true));
            problem(problems, "Datum", guard.fill(form.datum(), e.appliedDate().toString()));
            if (problems.isEmpty()) {
                problems.addAll(verify(page, e));
            }
            if (!problems.isEmpty()) {
                String message = "Not saved: " + String.join("; ", problems);
                logs.insert(runId, e, Result.FAILED, message, null);
                return Result.FAILED;
            }
            Outcome click = guard.saveActivity(); // the one and only Spara click for this job; never retried
            if (!click.done()) {
                logs.insert(runId, e, Result.FAILED, "Not saved: " + click.reason(), null);
                return Result.FAILED;
            }
            boolean confirmed = guard.waitForConfirmation(TOAST, List.of(e.employerLabel(), baseName(e.employerLabel())), toastWaitMs);
            if (!confirmed) {
                logs.insert(runId, e, Result.UNCONFIRMED, "Spara was clicked but the confirmation did not appear; check by hand", null);
                return Result.UNCONFIRMED;
            }
            Instant now = clock.instant();
            logs.insert(runId, e, Result.SAVED, "Saved", now);
            jobs.markLogged(e.jobId());
            return Result.SAVED;
        });
    }

    /** Reads the form back: every value must be on the page exactly as intended before Spara is clicked. */
    private List<String> verify(com.microsoft.playwright.Page page, Entry e) {
        List<FormField> now = PageScripts.extract(page);
        AktivitetForm.Located f = AktivitetForm.locate(now);
        List<String> problems = new ArrayList<>();
        expect(problems, "Yrkesroll", f.yrkesroll().value(), e.yrkesroll());
        expect(problems, "Arbetsgivaren", f.arbetsgivare().value(), e.employerLabel());
        expect(problems, "Ort", f.ort().value(), e.municipality());
        expect(problems, "Datum", f.datum().value(), e.appliedDate().toString());
        chosen(problems, "Omfattning", f.omfattning(), e.omfattning());
        chosen(problems, "Var finns jobbet", f.varFinns(), e.varFinns());
        chosen(problems, "Svarade du på en annons", f.annons(), e.answeredAd() ? "ja" : "nej");
        return problems;
    }

    private static void expect(List<String> problems, String name, String actual, String expected) {
        if (!expected.equals(actual == null ? "" : actual.strip())) {
            problems.add(name + " reads \"" + (actual == null ? "" : actual) + "\" instead of \"" + expected + "\"");
        }
    }

    private static void chosen(List<String> problems, String name, java.util.Map<String, FormField> group, String expected) {
        List<String> checked = group.entrySet().stream().filter(en -> en.getValue().checked()).map(java.util.Map.Entry::getKey).toList();
        if (!checked.equals(List.of(expected.toLowerCase(Locale.ROOT)))) {
            problems.add(name + " has " + (checked.isEmpty() ? "nothing" : checked) + " chosen instead of " + expected);
        }
    }

    private static void problem(List<String> problems, String field, Outcome o) {
        if (!o.done()) {
            problems.add(field + ": " + o.reason());
        }
    }

    private static String prefix(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static String baseName(String label) {
        int bracket = label.indexOf(" (");
        return bracket > 0 ? label.substring(0, bracket) : label;
    }

    /** Same employer and the same date already listed (SPEC 10: avoid duplicates). */
    static boolean isDuplicate(SavedActivities saved, Entry e) {
        String label = e.employerLabel().toLowerCase(Locale.ROOT);
        String base = baseName(e.employerLabel()).toLowerCase(Locale.ROOT);
        return saved.entries().stream().anyMatch(r -> r.date().equals(e.appliedDate().toString())
                && (r.text().toLowerCase(Locale.ROOT).contains(label) || r.text().toLowerCase(Locale.ROOT).contains(base)));
    }

    /** True once the page is not a login or BankID wall. The poll is read-only (hard rule 2). */
    private boolean waitForSignIn(long runId) {
        long deadline = System.currentTimeMillis() + 30 * 60_000L;
        while (System.currentTimeMillis() < deadline) {
            PageClassifier.Kind kind = browser.withTab(TAB, p -> PageClassifier.classify(PageScripts.detect(p)));
            if (kind != PageClassifier.Kind.WAITING_FOR_SIGN_IN) {
                return true;
            }
            runs.update(runId, PipelineModel.Status.WAITING_FOR_USER, "OPEN", 5,
                    "Waiting for you to sign in to Arbetsförmedlingen (BankID) in the JobPilot browser");
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Ends the run as FAILED and records the entries from index {@code from} on as SKIPPED with the reason. */
    private void stop(long runId, List<Entry> entries, int from, String message) {
        for (int i = from; i < entries.size(); i++) {
            logs.insert(runId, entries.get(i), Result.SKIPPED, "Not attempted: " + message, null);
        }
        runs.finish(runId, PipelineModel.Status.FAILED, message, clock.instant());
    }
}
