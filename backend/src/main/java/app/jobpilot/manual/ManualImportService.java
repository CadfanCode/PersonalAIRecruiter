package app.jobpilot.manual;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import app.jobpilot.claude.ClaudeResources;
import app.jobpilot.claude.ClaudeRunner;
import app.jobpilot.claude.ClaudeTask;
import app.jobpilot.common.ApiExceptions.ConflictException;
import app.jobpilot.common.ApiExceptions.NotFoundException;
import app.jobpilot.jobs.Dedupe;
import app.jobpilot.jobs.JobRepository;
import app.jobpilot.jobs.NewJob;
import app.jobpilot.pipeline.PipelineModel.Run;
import app.jobpilot.pipeline.PipelineModel.Status;
import app.jobpilot.pipeline.PipelineModel.Step;
import app.jobpilot.pipeline.RunOptions;
import app.jobpilot.pipeline.RunRepository;
import app.jobpilot.ranking.CvDigestService;
import app.jobpilot.ranking.ScoreService;
import app.jobpilot.search.SearchProfile;
import app.jobpilot.search.SearchProfileService;

/**
 * Adds one job the user found themselves (for example on LinkedIn) from its address. It then goes through the same steps as a
 * found job: a search profile, a score, and from the results table on to documents, form filling and the aktivitetsrapport.
 * The page is read once on the user's behalf when that is allowed; for sites that discourage bots, and whenever the page
 * cannot be read, the run waits for the user to paste the ad text instead.
 */
@Service
public class ManualImportService {

    private static final Logger log = LoggerFactory.getLogger(ManualImportService.class);
    static final int MIN_PASTED_CHARS = 200;

    /** Claude's answer for {@code extractJob}. */
    public record Extracted(boolean isJobAd, String title, String employer, String location, String municipality,
                            String language, String employmentType, String publishedDate, String deadline, String applyUrl,
                            String description) {
    }

    private final AdFetcher fetcher;
    private final PasteOnlyHosts pasteOnly;
    private final ClaudeRunner claude;
    private final JobRepository jobs;
    private final RunRepository runs;
    private final SearchProfileService profiles;
    private final CvDigestService digests;
    private final ScoreService scorer;
    private final ExecutorService executor;
    private final Clock clock;

    public ManualImportService(AdFetcher fetcher, PasteOnlyHosts pasteOnly, ClaudeRunner claude, JobRepository jobs,
                               RunRepository runs, SearchProfileService profiles, CvDigestService digests, ScoreService scorer,
                               ExecutorService pipelineExecutor, Clock clock) {
        this.fetcher = fetcher;
        this.pasteOnly = pasteOnly;
        this.claude = claude;
        this.jobs = jobs;
        this.runs = runs;
        this.profiles = profiles;
        this.digests = digests;
        this.scorer = scorer;
        this.executor = pipelineExecutor;
        this.clock = clock;
    }

    /** Starts a MANUAL run for the address; {@code text} is the ad text when the user already pasted it. Returns the run id. */
    public long start(String url, String text) {
        String address = url == null ? "" : url.strip();
        if (!address.matches("(?i)https?://\\S+")) {
            throw new IllegalArgumentException("Paste the full address of the job ad, starting with https://");
        }
        if (text != null && !text.isBlank() && text.strip().length() < MIN_PASTED_CHARS) {
            throw new IllegalArgumentException("The pasted text is too short to be a job ad");
        }
        long id = runs.create(new RunOptions(null, null, null, true, "MANUAL", null), clock.instant());
        runs.setSourceUrl(id, address);
        submit(id, () -> guarded(id, () -> read(id, address, text == null || text.isBlank() ? null : text.strip())));
        return id;
    }

    /** The user pasted the ad text for a run that was waiting for it. */
    public void provideText(long runId, String text) {
        Run run = runs.find(runId).orElseThrow(() -> new NotFoundException("No run " + runId));
        if (!"MANUAL".equals(run.options().modeName()) || run.status() != Status.WAITING_FOR_USER) {
            throw new ConflictException("This run is not waiting for the text of an ad");
        }
        if (text == null || text.strip().length() < MIN_PASTED_CHARS) {
            throw new IllegalArgumentException("The pasted text is too short to be a job ad");
        }
        String pasted = text.strip();
        runs.update(runId, Status.QUEUED, Step.FETCH, 15, "Reading the pasted text");
        submit(runId, () -> guarded(runId, () -> extractAndScore(runId, run.sourceUrl(), pasted)));
    }

    // ---- steps (on the pipeline executor) ----

    private void read(long id, String url, String pasted) {
        runs.update(id, Status.RUNNING, Step.FETCH, 5, "Reading the ad");
        if (pasted != null) {
            extractAndScore(id, url, pasted);
            return;
        }
        if (pasteOnly.matches(url)) {
            runs.update(id, Status.WAITING_FOR_USER, Step.FETCH, 10,
                    "This site discourages automatic reading, so JobPilot does not open it. Copy the ad text from your own browser and paste it below.");
            return;
        }
        String text;
        try {
            text = AdText.from(fetcher.fetch(url).html());
        } catch (AdFetcher.FetchException e) {
            runs.update(id, Status.WAITING_FOR_USER, Step.FETCH, 10, e.getMessage() + ". Copy the ad text from your browser and paste it below.");
            return;
        }
        if (!AdText.looksSubstantial(text)) {
            runs.update(id, Status.WAITING_FOR_USER, Step.FETCH, 10,
                    "The page did not contain enough text to be an ad (it may need a login or load its text with a script). Copy the ad text from your browser and paste it below.");
            return;
        }
        extractAndScore(id, url, text);
    }

    private void extractAndScore(long id, String url, String text) {
        LocalDate today = LocalDate.now(clock);
        runs.update(id, Status.RUNNING, Step.FETCH, 25, "Reading the ad text");
        Extracted ad = claude.run(ClaudeTask.of("extractJob", ClaudeResources.prompt("extractJob", Map.of(
                "url", url == null ? "" : url, "today", today.toString(),
                "text", text.length() > AdText.MAX_CHARS ? text.substring(0, AdText.MAX_CHARS) : text)),
                ClaudeResources.schema("extractJob"), Extracted.class));
        if (!ad.isJobAd() || blank(ad.title()) || blank(ad.employer()) || blank(ad.description())) {
            throw new IllegalStateException("That text does not look like a single job ad, so nothing was added");
        }

        NewJob job = new NewJob("MANUAL", url, url, blank(ad.applyUrl()) ? null : ad.applyUrl().strip(), ad.title().strip(),
                ad.employer().strip(), null, ad.location(), blank(ad.municipality()) ? null : ad.municipality().strip(),
                date(ad.publishedDate(), false), date(ad.deadline(), true), ad.language(), ad.employmentType(),
                ad.description().strip(), "{}", ad.publishedDate() == null, url, 0, false);
        // The same ad may already be stored from Platsbanken: use that row rather than overwrite its apply link.
        long jobId = jobs.idByDedupeKey(job.dedupeKey()).orElseGet(() -> jobs.upsert(job, id).id());
        jobs.linkToRun(id, jobId, 1.0);

        runs.update(id, Status.RUNNING, Step.PROFILE, 45, "Working out what kind of role this is");
        String about = "Pasted job ad: " + job.title() + " at " + job.employer()
                + (job.municipality() == null ? "" : " in " + job.municipality()) + ". "
                + job.descriptionText().substring(0, Math.min(500, job.descriptionText().length()));
        long profileId = profiles.build(about, new RunOptions(null, null, null, true, "MANUAL", null), today);
        runs.setSearchProfile(id, profileId);
        SearchProfile profile = profiles.get(profileId).profile();

        runs.update(id, Status.RUNNING, Step.RANK, 70, "Scoring the ad against your CV");
        ScoreService.Summary result = scorer.score(List.of(jobId), profileId, profile, digests.build(), done -> { }, () -> false);
        if (result.failed() > 0) {
            throw new IllegalStateException("The ad was added but scoring failed: " + result.errors().get(0));
        }
        runs.finish(id, Status.DONE, "Added “" + job.title() + "” at " + job.employer()
                + (jobs.idByDedupeKey(job.dedupeKey()).isPresent() && !job.url().equals(url) ? "" : ""), clock.instant());
    }

    // ---- plumbing ----

    private void submit(long id, Runnable task) {
        try {
            executor.submit(task);
        } catch (RejectedExecutionException e) {
            runs.finish(id, Status.FAILED, "Too many runs in progress; try again shortly", clock.instant());
            throw new ConflictException("Too many runs in progress; try again shortly");
        }
    }

    private void guarded(long id, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            log.warn("Importing the ad of run {} failed", id, e);
            runs.finish(id, Status.FAILED, e.getMessage() == null ? e.toString() : e.getMessage(), clock.instant());
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static Instant date(String iso, boolean endOfDay) {
        if (blank(iso)) {
            return null;
        }
        LocalDate d = LocalDate.parse(iso);
        return (endOfDay ? d.atTime(23, 59) : d.atStartOfDay()).toInstant(ZoneOffset.UTC);
    }
}
