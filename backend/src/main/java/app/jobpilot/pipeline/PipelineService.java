package app.jobpilot.pipeline;

import java.time.Clock;
import java.time.Duration;
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

import app.jobpilot.common.ApiExceptions.ConflictException;
import app.jobpilot.common.ApiExceptions.NotFoundException;
import app.jobpilot.discovery.RoleDiscoveryService;
import app.jobpilot.discovery.RoleDiscoveryService.ResolvedRole;
import app.jobpilot.jobs.JobPrefilter;
import app.jobpilot.jobs.JobRepository;
import app.jobpilot.jobs.NewJob;
import app.jobpilot.pipeline.PipelineModel.Run;
import app.jobpilot.pipeline.PipelineModel.RunView;
import app.jobpilot.pipeline.PipelineModel.Status;
import app.jobpilot.pipeline.PipelineModel.Step;
import app.jobpilot.ranking.CvDigestService;
import app.jobpilot.ranking.ScoreService;
import app.jobpilot.country.CountryRegistry;
import app.jobpilot.country.JobSource;
import app.jobpilot.search.SearchProfile;
import app.jobpilot.search.SearchProfileService;
import app.jobpilot.search.WebDiscoveryService;
import app.jobpilot.settings.AppSettings;
import app.jobpilot.settings.JobPilotProperties;

/**
 * Runs the pipeline PROFILE, SEARCH, RANK on a bounded executor (SPEC 3). The UI polls
 * {@code /api/runs/{id}}. A run pauses in WAITING_FOR_USER after PROFILE unless autoContinue is set.
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final RunRepository runs;
    private final SearchProfileService profiles;
    private final CountryRegistry countries;
    private final AppSettings settings;
    private final WebDiscoveryService web;
    private final JobRepository jobs;
    private final CvDigestService digests;
    private final ScoreService scorer;
    private final ExecutorService executor;
    private final Clock clock;
    private final JobPilotProperties props;
    private final RoleDiscoveryService discovery;
    private final Set<Long> cancelRequested = ConcurrentHashMap.newKeySet();

    /** Ads scored by a Discover run when the user sets no limit: the normal and the aggressive default, and the hard cap. */
    static final int DISCOVER_LIMIT = 60;
    static final int DISCOVER_AGGRESSIVE_LIMIT = 150;
    static final int DISCOVER_MAX_LIMIT = 150;
    static final int AGGRESSIVE_MAX_AGE_DAYS = 14;
    /** Hard cap of ads scored by one run, in every mode. */
    static final int MAX_LIMIT = 150;
    /** Progress range of the scoring step. */
    static final int RANK_START = 45;
    static final int RANK_END = 95;
    /** Settings key: also search employers' own career pages (slower, uses web search). Off by default. */
    public static final String CAREER_PAGES_KEY = "search.careerPages";
    static final String DISCOVER_DEFAULT_CONTEXT = "Any suitable job within commuting distance of the candidate's city, full time or part time";

    public PipelineService(RunRepository runs, SearchProfileService profiles, CountryRegistry countries,
                           WebDiscoveryService web, JobRepository jobs, CvDigestService digests, ScoreService scorer,
                           RoleDiscoveryService discovery, ExecutorService pipelineExecutor, Clock clock, JobPilotProperties props,
                           AppSettings settings) {
        this.settings = settings;
        this.runs = runs;
        this.profiles = profiles;
        this.countries = countries;
        this.web = web;
        this.jobs = jobs;
        this.digests = digests;
        this.scorer = scorer;
        this.discovery = discovery;
        this.executor = pipelineExecutor;
        this.clock = clock;
        this.props = props;
    }

    public long start(String prompt, RunOptions options) {
        boolean discover = options.discover();
        if (!discover && (prompt == null || prompt.isBlank())) {
            throw new IllegalArgumentException("prompt is required");
        }
        validate(options);
        long id = runs.create(options, clock.instant());
        String text = prompt == null ? "" : prompt.trim();
        submit(id, () -> {
            if (discover) {
                suggestRoles(id, text, options);
            } else {
                buildProfile(id, text, options);
            }
        });
        return id;
    }

    /** The role suggestions of a Discover run, kept so the user can revisit them. */
    public List<RoleDiscoveryService.RoleView> roles(long id) {
        runs.find(id).orElseThrow(() -> new NotFoundException("No run " + id));
        return discovery.views(id);
    }

    /** Applies the user's selection, renames and added roles. Only while the run waits for them. */
    public List<RoleDiscoveryService.RoleView> saveRoles(long id, List<RoleDiscoveryService.RoleEdit> edits) {
        Run run = runs.find(id).orElseThrow(() -> new NotFoundException("No run " + id));
        if (!run.options().discover() || run.status() != Status.WAITING_FOR_USER) {
            throw new ConflictException("Roles can only be changed while a Discover run waits for your selection");
        }
        discovery.saveSelection(id, edits == null ? List.of() : edits);
        return discovery.views(id);
    }

    public RunView view(long id) {
        Run run = runs.find(id).orElseThrow(() -> new NotFoundException("No run " + id));
        SearchProfile profile = run.searchProfileId() == null ? null : profiles.get(run.searchProfileId()).profile();
        return new RunView(run, profile, etaSeconds(run));
    }

    /**
     * Time left while ads are being scored, from how long this step has taken for the share of it that is done. Null while the
     * run is in any other step or has not progressed far enough to say.
     */
    Integer etaSeconds(Run run) {
        if (run.status() != Status.RUNNING || !Step.RANK.equals(run.step()) || run.stepStartedAt() == null) {
            return null;
        }
        double done = (run.progress() - RANK_START) / (double) (RANK_END - RANK_START);
        if (done < 0.05 || done >= 1) {
            return null;
        }
        double elapsed = Duration.between(run.stepStartedAt(), clock.instant()).toMillis() / 1000.0;
        return (int) Math.round(elapsed * (1 - done) / done);
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
        if (run.options().discover() && discovery.selected(id).isEmpty()) {
            throw new ConflictException("Choose at least one role to search for");
        }
        runs.update(id, Status.QUEUED, Step.SEARCH, 10, "Queued for search");
        submit(id, () -> {
            if (run.options().discover()) {
                discoverSearch(id);
            } else {
                searchAndRank(id);
            }
        });
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

    // ---- Discover roles ----

    /** PROFILE (the user's own constraints), then SUGGEST (role types from the whole CV), then wait for the user's choice. */
    private void suggestRoles(long id, String context, RunOptions options) {
        guarded(id, () -> {
            runs.update(id, Status.RUNNING, Step.PROFILE, 5, "Reading your constraints");
            long profileId = profiles.build(context.isBlank() ? DISCOVER_DEFAULT_CONTEXT : context, options, LocalDate.now(clock));
            if (options.aggressiveSearch() && options.maxAgeDays() == null
                    && profiles.get(profileId).profile().maxAgeDays() < AGGRESSIVE_MAX_AGE_DAYS) {
                profileId = profiles.withMaxAgeDays(profileId, AGGRESSIVE_MAX_AGE_DAYS);
            }
            runs.setSearchProfile(id, profileId);
            checkCancelled(id);
            runs.update(id, Status.RUNNING, Step.SUGGEST, 15, "Reading your whole CV and thinking of roles you would fit");
            discovery.suggest(id, context, options.aggressiveSearch(), LocalDate.now(clock));
            runs.update(id, Status.WAITING_FOR_USER, Step.SUGGEST, 40, "Choose the roles to search for, then continue");
        });
    }

    private void discoverSearch(long id) {
        guarded(id, () -> discoverSearchNow(id));
    }

    /** An ad found by one or more roles: the version with the best relevance, its best role, and every role that found it. */
    private static final class Candidate {
        NewJob job;
        int primary;
        final Set<Integer> roles = new java.util.LinkedHashSet<>();

        Candidate(NewJob job, int role) {
            this.job = job;
            this.primary = role;
            roles.add(role);
        }
    }

    private void discoverSearchNow(long id) {
        Run run = runs.find(id).orElseThrow();
        RunOptions options = run.options();
        SearchProfile base = profiles.get(run.searchProfileId()).profile();
        runs.update(id, Status.RUNNING, Step.SEARCH, 10, "Preparing the search for your chosen roles");
        List<ResolvedRole> roles = discovery.resolve(id, base, LocalDate.now(clock));
        int limit = Math.min(options.limit() != null ? options.limit()
                : (options.aggressiveSearch() ? DISCOVER_AGGRESSIVE_LIMIT : DISCOVER_LIMIT), DISCOVER_MAX_LIMIT);
        List<String> notes = new ArrayList<>();

        java.util.Map<String, Candidate> merged = new java.util.LinkedHashMap<>();
        int found = 0;
        int failedRoles = 0;
        for (int i = 0; i < roles.size(); i++) {
            checkCancelled(id);
            ResolvedRole role = roles.get(i);
            runs.progress(id, 10 + 30 * i / roles.size(), "Searching " + countries.source().boardName() + " for " + role.title() + " (" + (i + 1) + " of " + roles.size() + ")");
            JobSource.Collected collected;
            try {
                collected = countries.source().collect(role.profile(), (done, total) -> { });
            } catch (RuntimeException e) {
                failedRoles++;
                notes.add(role.title() + ": " + e.getMessage());
                continue;
            }
            collected.notes().stream().filter(n -> !notes.contains(n)).forEach(notes::add);
            JobPrefilter.Result filtered = JobPrefilter.apply(collected.jobs(), role.profile(), clock.instant());
            found += collected.jobs().size();
            for (NewJob job : filtered.kept()) {
                Candidate existing = merged.get(job.dedupeKey());
                if (existing == null) {
                    merged.put(job.dedupeKey(), new Candidate(job, i));
                } else {
                    existing.roles.add(i);
                    if (job.relevance() > existing.job.relevance()) {
                        existing.job = job;
                        existing.primary = i;
                    }
                }
            }
        }
        if (failedRoles == roles.size()) {
            throw new IllegalStateException("Every role search failed: " + String.join("; ", notes));
        }
        checkCancelled(id);

        // Store every ad of every role, tagged with the roles that found it.
        java.util.Map<Candidate, Long> ids = new java.util.LinkedHashMap<>();
        for (Candidate c : merged.values()) {
            long jobId = jobs.upsert(c.job, id).id();
            jobs.linkToRun(id, jobId, c.job.relevance());
            jobs.setPrimaryRole(id, jobId, roles.get(c.primary).id());
            c.roles.forEach(r -> jobs.tagRole(id, jobId, roles.get(r).id()));
            ids.put(c, jobId);
        }

        // Score at most `limit` ads, taken round-robin from the roles so one broad role cannot use the whole budget.
        java.util.Map<Integer, java.util.ArrayDeque<Candidate>> perRole = new java.util.TreeMap<>();
        merged.values().stream().sorted(Comparator.comparingDouble((Candidate c) -> -c.job.relevance()))
                .forEach(c -> perRole.computeIfAbsent(c.primary, k -> new java.util.ArrayDeque<>()).add(c));
        java.util.Map<Integer, List<Long>> toScore = new java.util.TreeMap<>();
        int picked = 0;
        while (picked < limit && perRole.values().stream().anyMatch(q -> !q.isEmpty())) {
            for (var entry : perRole.entrySet()) {
                Candidate next = entry.getValue().poll();
                if (next != null && picked < limit) {
                    toScore.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(ids.get(next));
                    picked++;
                }
            }
        }

        String summary = "Found " + found + " ads for " + roles.size() + " roles, " + merged.size() + " distinct after filters, scoring " + picked;
        runs.update(id, Status.RUNNING, Step.RANK, RANK_START, summary);
        checkCancelled(id);
        CvDigestService.Digest cv = digests.build();
        int total = picked;
        int[] doneBefore = {0};
        int scored = 0;
        int cached = 0;
        int failed = 0;
        String firstError = null;
        for (var entry : toScore.entrySet()) {
            checkCancelled(id);
            ResolvedRole role = roles.get(entry.getKey());
            int base0 = doneBefore[0];
            ScoreService.Summary result = scorer.score(entry.getValue(), role.searchProfileId(), role.profile(), cv,
                    done -> runs.progress(id, RANK_START + (total == 0 ? RANK_END - RANK_START : (RANK_END - RANK_START) * (base0 + done) / total), "Scored " + (base0 + done) + " of " + total),
                    () -> cancelRequested.contains(id));
            doneBefore[0] += entry.getValue().size();
            scored += result.scored();
            cached += result.cached();
            failed += result.failed();
            if (firstError == null && !result.errors().isEmpty()) {
                firstError = result.errors().get(0);
            }
        }
        checkCancelled(id);
        if (total > 0 && failed == total) {
            throw new IllegalStateException("Every scoring call failed: " + firstError);
        }
        StringBuilder message = new StringBuilder(summary).append(". Scored ").append(scored);
        if (cached > 0) {
            message.append(", ").append(cached).append(" from cache");
        }
        if (failed > 0) {
            message.append(", ").append(failed).append(" failed");
        }
        if (merged.size() > picked) {
            message.append(". ").append(merged.size() - picked).append(" more ads were found but not scored (limit ").append(limit).append(")");
        }
        notes.forEach(n -> message.append(". ").append(n));
        runs.finish(id, Status.DONE, message.toString(), clock.instant());
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

        runs.update(id, Status.RUNNING, Step.SEARCH, 10, "Searching " + countries.source().boardName());
        JobSource.Collected collected = countries.source().collect(profile, (done, total) ->
                runs.progress(id, 10 + 30 * done / total, "Searched " + done + " of " + total + " queries"));
        List<NewJob> candidates = new ArrayList<>(collected.jobs());
        List<String> notes = new ArrayList<>(collected.notes());

        if (options.web() || settings.getBoolean(CAREER_PAGES_KEY, false)) {
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
        runs.update(id, Status.RUNNING, Step.RANK, RANK_START, summary);
        checkCancelled(id);

        CvDigestService.Digest cv = digests.build();
        ScoreService.Summary result = scorer.score(toScore, profileId, profile, cv,
                done -> runs.progress(id, RANK_START + (toScore.isEmpty() ? RANK_END - RANK_START : (RANK_END - RANK_START) * done / toScore.size()),
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
        if (o.limit() != null && (o.limit() < 1 || o.limit() > MAX_LIMIT)) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (!java.util.Set.of("TARGETED", "DISCOVER", "MANUAL").contains(o.modeName())) {
            throw new IllegalArgumentException("mode must be TARGETED or DISCOVER");
        }
    }
}
