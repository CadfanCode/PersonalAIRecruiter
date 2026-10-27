package se.caibirch.jobpilot.search;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import se.caibirch.jobpilot.claude.ClaudeResources;
import se.caibirch.jobpilot.claude.ClaudeRunner;
import se.caibirch.jobpilot.claude.ClaudeTask;
import se.caibirch.jobpilot.jobs.NewJob;

/** Optional second source (SPEC 6.2): Claude with WebSearch and WebFetch finds ads on career pages. */
@Service
public class WebDiscoveryService {

    public record WebJob(String url, String title, String employer, String publishedDate,
                         boolean dateVerifiedOnAdPage, String deadline, String location, String language,
                         String description) {
    }

    public record Found(List<WebJob> jobs) {
    }

    private final ClaudeRunner claude;
    private final ObjectMapper mapper;

    public WebDiscoveryService(ClaudeRunner claude, ObjectMapper mapper) {
        this.claude = claude;
        this.mapper = mapper;
    }

    public List<NewJob> discover(SearchProfile profile, int limit, LocalDate today) {
        String profileJson;
        try {
            profileJson = mapper.writeValueAsString(profile);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        Found found = claude.run(new ClaudeTask<>("discoverJobsWeb",
                ClaudeResources.prompt("discoverJobsWeb", Map.of("today", today.toString(), "profile", profileJson,
                        "maxAgeDays", String.valueOf(profile.maxAgeDays()), "limit", String.valueOf(limit))),
                ClaudeResources.schema("discoverJobsWeb"), Found.class, true));
        return found.jobs().stream().map(WebDiscoveryService::toNewJob).toList();
    }

    /** Ads whose date was not read on the ad page are flagged "date unverified" and sort last. */
    static NewJob toNewJob(WebJob w) {
        boolean verified = w.dateVerifiedOnAdPage() && w.publishedDate() != null;
        Instant published = verified ? LocalDate.parse(w.publishedDate()).atStartOfDay().toInstant(ZoneOffset.UTC) : null;
        Instant deadline = w.deadline() == null ? null : LocalDate.parse(w.deadline()).atTime(23, 59).toInstant(ZoneOffset.UTC);
        return new NewJob("WEB", w.url(), w.url(), w.url(), w.title(), w.employer(), null, w.location(),
                w.location(), published, deadline, w.language(), null, w.description(), null, !verified, w.url(),
                0, false);
    }
}
