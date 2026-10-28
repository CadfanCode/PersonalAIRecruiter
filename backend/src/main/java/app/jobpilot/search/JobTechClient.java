package app.jobpilot.search;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import app.jobpilot.settings.JobPilotProperties;

/** Client for the JobTech JobSearch API (SPEC 6.1). No key is needed; an optional one is sent as api-key. */
@Component
public class JobTechClient {

    static final int PAGE_SIZE = 100;
    /** The API only serves offsets up to 2000. */
    static final int MAX_OFFSET = 2000;

    private final RestClient http;
    private final RateLimiter limiter;
    private final Duration backoff;

    @Autowired
    public JobTechClient(JobPilotProperties props) {
        this(props.jobtech().baseUrl(), props.jobtech().apiKey(), props.jobtech().requestsPerSecond(),
                Duration.ofMillis(500));
    }

    JobTechClient(String baseUrl, String apiKey, int requestsPerSecond, Duration backoff) {
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("api-key", apiKey);
        }
        this.http = builder.build();
        this.limiter = new RateLimiter(requestsPerSecond);
        this.backoff = backoff;
    }

    /**
     * Fetches up to {@code maxHits} ads for one free-text query, paging 100 at a time, best match first.
     *
     * @param municipalityIds taxonomy concept ids; empty means the whole country
     */
    public List<JsonNode> search(String query, int maxAgeDays, List<String> municipalityIds, boolean remoteOnly,
                                 int maxHits) {
        List<JsonNode> hits = new ArrayList<>();
        int offset = 0;
        while (hits.size() < maxHits && offset <= MAX_OFFSET) {
            int limit = Math.min(PAGE_SIZE, maxHits - hits.size());
            int pageOffset = offset;
            JsonNode page = HttpRetry.call("JobTech search '" + query + "'", backoff, () -> {
                limiter.acquire();
                return http.get().uri(uri -> {
                    var b = uri.path("/search").queryParam("q", "{q}")
                            .queryParam("published-after", maxAgeDays * 24 * 60)
                            .queryParam("limit", limit).queryParam("offset", pageOffset);
                    municipalityIds.forEach(id -> b.queryParam("municipality", id));
                    if (remoteOnly) {
                        b.queryParam("remote", true);
                    }
                    return b.build(Map.of("q", query));
                }).header("Accept", "application/json").retrieve().body(JsonNode.class);
            });
            JsonNode pageHits = page == null ? null : page.get("hits");
            if (pageHits == null || !pageHits.isArray() || pageHits.isEmpty()) {
                break;
            }
            pageHits.forEach(hits::add);
            long total = page.path("total").path("value").asLong(0);
            offset += pageHits.size();
            if (offset >= total) {
                break;
            }
        }
        return hits;
    }
}
