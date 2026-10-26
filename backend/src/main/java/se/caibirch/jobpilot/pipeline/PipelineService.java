package se.caibirch.jobpilot.pipeline;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import se.caibirch.jobpilot.common.ApiExceptions.ConflictException;
import se.caibirch.jobpilot.common.ApiExceptions.NotFoundException;
import se.caibirch.jobpilot.jobs.JobPrefilter;
import se.caibirch.jobpilot.jobs.JobRepository;
import se.caibirch.jobpilot.jobs.NewJob;
import se.caibirch.jobpilot.pipeline.PipelineModel.Run;
import se.caibirch.jobpilot.pipeline.PipelineModel.RunView;
import se.caibirch.jobpilot.pipeline.PipelineModel.Status;
import se.caibirch.jobpilot.pipeline.PipelineModel.Step;
import se.caibirch.jobpilot.ranking.CvDigestService;
import se.caibirch.jobpilot.ranking.ScoreService;
import se.caibirch.jobpilot.search.CandidateCollector;
import se.caibirch.jobpilot.search.SearchProfile;
import se.caibirch.jobpilot.search.SearchProfileService;
import se.caibirch.jobpilot.search.WebDiscoveryService;
import se.caibirch.jobpilot.settings.JobPilotProperties;

/**
 * Runs the pipeline PROFILE, SEARCH, RANK on a bounded executor (SPEC 3). The UI polls
 * {@code /api/runs/{id}}. A run pauses in WAITING_FOR_USER after PROFILE unless autoContinue is set.
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final RunRepository runs;
    private final SearchProfileService profiles;
    private final CandidateCollector collector;
    private final WebDiscoveryService web;
    private final JobRepository jobs;
    private final CvDigestService digests;
    private final ScoreService scorer;
    private final ExecutorService executor;
    private final Clock clock;
    private final JobPilotProperties props;
    private final Set<Long> cancelRequested = ConcurrentHashMap.newKeySet();

    public PipelineService(RunRepository runs, SearchProfileService profiles, CandidateCollector collector,
                           WebDiscoveryService web, JobRepository jobs, CvDigestService digests, ScoreService scorer,
                           ExecutorService pipelineExecutor, Clock clock, JobPilotProperties props) {
        this.runs = runs;
        this.profiles = profiles;
        this.collector = collector;
        this.web = web;
        this.jobs = jobs;
        this.digests = digests;
        this.scorer = scorer;
        this.executor = pipelineExecutor;
        this.clock = clock;
        this.props = props;
    }

    public long start(String prompt, RunOptions options) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        validate(options);
        long id = runs.create(options, clock.instant());
        submit(id, () -> buildProfile(id, prompt.trim(), options));
        return id;
    }

    public RunView view(long id) {
        Run run = runs.find(id).orElseThrow(() -> new NotFoundException("No run " + id));
        SearchProfile profile = run.searchProfileId() == null ? null : profiles.get(run.searchProfileId()).profile();
        return new RunView(run, profile);
    }

    public List<Run> list() {
        return runs.list(30);
    }

    /** Replaces the profile with the user's edit. Only while the run waits for review. */
    public RunView editProfile(long id, JsonNode edited) {
        Run run = runs.find(id).orElseThrow(() -> new NotFoundException("No run " + id));
        if (run.status() != Status.WAITING_FOR_USER) {
            throw new ConflictException("The search profile can only be edited while the run waits for review");
        }
        long newId = profiles.saveEdited(run.prompt(), edited);
        runs.setSearchProfile(id, newId);
        return view(id);
    }

    public RunView continueRun(long id) {
        Run run = runs.find(id).orElseThrow(() -> new NotFoundException("No run " + id));
        if (run.status() != Status.WAITING_FOR_USER) {
            throw new ConflictException("Run " + id + " is not waiting for review (status " + run.status() + ")");
        }
        runs.update(id, Status.QUEUED, Step.SEARCH, 10, "Queued for search");
        submit(id, () -> searchAndRank(id));
        return view(id);
    }

    public RunView cancel(long id) {
        Run run = runs.find(id).orElseThrow(() -> new NotFoundException("No run " + id));
        switch (run.status()) {
            case DONE, FAILED, CANCELLED -> throw new ConflictException("Run " + id + " has already ended");
            case WAITING_FOR_USER -> runs.finish(id, Status.CANCELLED, "Cancelled by the user", clock.instant());
            default -> cancelRequested.add(id);
        }
        return view(id);
    }

    // ---- steps (run on the executor) ----

    private void buildProfile(long id, String prompt, RunOptions options) {
        guarded(id, () -> {
            runs.update(id, Status.RUNNING, Step.PROFILE, 5, "Reading your prompt");
            long profileId = profiles.build(prompt, options, LocalDate.now(clock));
            runs.setSearchProfile(id, profileId);
            if (options.auto()) {
                searchAndRankNow(id);
            } else {
                runs.update(id, Status.WAITING_FOR_USER, Step.PROFILE, 10, "Review the search profile, then continue");
            }
        });
    }

    private void searchAndRank(long id) {
        guarded(id, () -> searchAndRankNow(id));
    }

    private void searchAndRankNow(long id) {
        Run run = runs.find(id).orElseThrow();
        SearchProfile profile = profiles.get(run.searchProfileId()).profile();
        long profileId = run.searchProfileId();
        RunOptions options = run.options();
        int limit = options.limit() != null ? options.limit() : props.search().defaultLimit();

        runs.update(id, Status.RUNNING, Step.SEARCH, 10, "Searching Platsbanken");
        CandidateCollector.Collected collected = collector.collect(profile, (done, total) ->
                runs.progress(id, 10 + 30 * done / total, "Searched " + done + " of " + total + " queries"));
        List<NewJob> candidates = new ArrayList<>(collected.jobs());
        List<String> notes = new ArrayList<>(collected.notes());

        if (options.web()) {
            checkCancelled(id);
            runs.progress(id, 40, "Searching career pages");
            try {
                candidates.addAll(web.discover(profile, limit, LocalDate.now(clock)));
            } catch (RuntimeException e) {
                notes.add("Web discovery failed: " + e.getMessage());
            }
        }
        checkCancelled(id);

        JobPrefilter.Result filtered = JobPrefilter.apply(candidates, profile, clock.instant());
        List<NewJob> kept = filtered.kept();
        List<Long> stored = new ArrayList<>();
        for (NewJob job : kept) {
            long jobId = jobs.upsert(job, id).id();
            jobs.linkToRun(id, jobId, job.relevance());
            stored.add(jobId);
        }
        // Unverified web dates sort last, so they are scored last when the limit bites.
        List<Long> toScore = bestFirst(kept, stored).stream().limit(limit).toList();

        String dropped = filtered.dropped().isEmpty() ? "" : " (dropped: " + filtered.dropSummary() + ")";
        String summary = "Found " + candidates.size() + " ads, " + kept.size() + " after filters" + dropped
                + ", scoring " + toScore.size();
        runs.update(id, Status.RUNNING, Step.RANK, 45, summary);
        checkCancelled(id);

        CvDigestService.Digest cv = digests.build();
        ScoreService.Summary result = scorer.score(toScore, profileId, profile, cv,
                done -> runs.progress(id, 45 + (toScore.isEmpty() ? 50 : 50 * done / toScore.size()),
                        "Scored " + done + " of " + toScore.size()),
                () -> cancelRequested.contains(id));
        checkCancelled(id);

        if (!toScore.isEmpty() && result.failed() == toScore.size()) {
            throw new IllegalStateException("Every scoring call failed: " + result.errors().get(0));
        }
        StringBuilder message = new StringBuilder(summary).append(". Scored ").append(result.scored());
        if (result.cached() > 0) {
            message.append(", ").append(result.cached()).append(" from cache");
        }
        if (result.failed() > 0) {
            message.append(", ").append(result.failed()).append(" failed");
        }
        notes.forEach(n -> message.append(". ").append(n));
        runs.finish(id, Status.DONE, message.toString(), clock.instant());
    }

    private static List<Long> bestFirst(List<NewJob> kept, List<Long> ids) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing((Integer i) -> kept.get(i).dateUnverified())
                .thenComparing(i -> -kept.get(i).relevance()));
        return order.stream().map(ids::get).distinct().toList();
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
        } catch (CancelledException e) {
            runs.finish(id, Status.CANCELLED, "Cancelled by the user", clock.instant());
        } catch (RuntimeException e) {
            log.error("Run {} failed", id, e);
            runs.finish(id, Status.FAILED, e.getMessage() == null ? e.toString() : e.getMessage(), clock.instant());
        } finally {
            cancelRequested.remove(id);
        }
    }

    private void checkCancelled(long id) {
        if (cancelRequested.contains(id)) {
            throw new CancelledException();
        }
    }

    private static final class CancelledException extends RuntimeException {
        CancelledException() {
            super(null, null, false, false);
        }
    }

    private void validate(RunOptions o) {
        if (o.maxAgeDays() != null && (o.maxAgeDays() < 1 || o.maxAgeDays() > 90)) {
            throw new IllegalArgumentException("maxAgeDays must be between 1 and 90");
        }
        if (o.limit() != null && (o.limit() < 1 || o.limit() > 200)) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
    }
}
