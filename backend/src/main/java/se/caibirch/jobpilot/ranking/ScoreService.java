package se.caibirch.jobpilot.ranking;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import se.caibirch.jobpilot.claude.ClaudeResources;
import se.caibirch.jobpilot.claude.ClaudeRunner;
import se.caibirch.jobpilot.claude.ClaudeTask;
import se.caibirch.jobpilot.documents.NewGraduatePhrases;
import se.caibirch.jobpilot.jobs.JobModel.Score;
import se.caibirch.jobpilot.jobs.JobRepository;
import se.caibirch.jobpilot.jobs.JobRepository.AdText;
import se.caibirch.jobpilot.search.SearchProfile;
import se.caibirch.jobpilot.settings.JobPilotProperties;

/**
 * Scores ads with the {@code scoreJob} task, two at a time (SPEC 7.3). Results are cached by
 * (job, search profile, CV database version). No database transaction is open during a model call:
 * each result is saved in its own short statement.
 */
@Service
public class ScoreService {

    public record Summary(int scored, int cached, int failed, List<String> errors) {
    }

    private static final Logger log = LoggerFactory.getLogger(ScoreService.class);
    private static final int CONCURRENCY = 2;

    private final ClaudeRunner claude;
    private final JobRepository jobs;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final JobPilotProperties props;

    public ScoreService(ClaudeRunner claude, JobRepository jobs, ObjectMapper mapper, Clock clock,
                        JobPilotProperties props) {
        this.claude = claude;
        this.jobs = jobs;
        this.mapper = mapper;
        this.clock = clock;
        this.props = props;
    }

    /**
     * @param onDone called after each job with the number finished so far (scored, cached or failed)
     */
    public Summary score(List<Long> jobIds, long searchProfileId, SearchProfile profile, CvDigestService.Digest cv,
                         IntConsumer onDone, BooleanSupplier cancelled) {
        String profileJson = json(profile);
        AtomicInteger scored = new AtomicInteger();
        AtomicInteger cached = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY)) {
            for (long id : jobIds) {
                pool.submit(() -> {
                    try {
                        if (cancelled.getAsBoolean()) {
                            return;
                        }
                        if (jobs.hasScore(id, searchProfileId, cv.version())) {
                            cached.incrementAndGet();
                        } else {
                            scoreOne(id, searchProfileId, profileJson, cv);
                            scored.incrementAndGet();
                        }
                    } catch (RuntimeException e) {
                        log.warn("Scoring job {} failed: {}", id, e.getMessage());
                        errors.add("job " + id + ": " + e.getMessage());
                    } finally {
                        onDone.accept(finished.incrementAndGet());
                    }
                });
            }
        }
        return new Summary(scored.get(), cached.get(), errors.size(), List.copyOf(errors));
    }

    private void scoreOne(long jobId, long searchProfileId, String profileJson, CvDigestService.Digest cv) {
        AdText ad = jobs.adText(jobId).orElseThrow(() -> new IllegalStateException("job vanished"));
        JobScoring.Raw raw = claude.run(ClaudeTask.of("scoreJob",
                ClaudeResources.prompt("scoreJob", Map.of("profile", profileJson, "cv", cv.json(),
                        "ad", AdFormatter.format(ad, mapper))),
                ClaudeResources.schema("scoreJob"), JobScoring.Raw.class));
        String model = props.claude().model() == null || props.claude().model().isBlank()
                ? "claude-default" : props.claude().model();
        Score score = JobScoring.toScore(raw, model, clock.instant());
        jobs.saveScore(jobId, searchProfileId, cv.version(), score);
        // The scorer missed "i början av din karriär" once; the same phrase list the validator uses decides too.
        jobs.applyScoreFindings(jobId, raw.asksForNewGraduates() || NewGraduatePhrases.appearsIn(ad.description()),
                raw.experienceRequiredText() == null || raw.experienceRequiredText().isBlank() ? null
                        : raw.experienceRequiredText(), raw.language());
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
