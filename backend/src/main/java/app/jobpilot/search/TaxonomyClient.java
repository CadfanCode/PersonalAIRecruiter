package app.jobpilot.search;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import app.jobpilot.settings.JobPilotProperties;

/** Resolves municipality names to JobTech taxonomy concept ids (SPEC 6.1). Loaded once, on first use. */
@Component
public class TaxonomyClient {

    private final RestClient http;
    private final Duration backoff;
    private volatile Map<String, String> municipalities;

    @Autowired
    public TaxonomyClient(JobPilotProperties props) {
        this(props.jobtech().taxonomyUrl(), Duration.ofMillis(500));
    }

    TaxonomyClient(String baseUrl, Duration backoff) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
        this.backoff = backoff;
    }

    /** Concept ids for the names that resolve; unknown names are left out. */
    public List<String> municipalityIds(List<String> names) {
        Map<String, String> all = load();
        return names.stream().map(n -> all.get(key(n))).filter(java.util.Objects::nonNull).distinct().toList();
    }

    private Map<String, String> load() {
        Map<String, String> cached = municipalities;
        if (cached != null) {
            return cached;
        }
        JsonNode body = HttpRetry.call("taxonomy municipalities", backoff, () -> http.get()
                .uri("/v1/taxonomy/main/concepts?type=municipality&limit=400")
                .header("Accept", "application/json").retrieve().body(JsonNode.class));
        Map<String, String> loaded = new HashMap<>();
        if (body != null && body.isArray()) {
            for (JsonNode c : body) {
                loaded.put(key(c.path("taxonomy/preferred-label").asText()), c.path("taxonomy/id").asText());
            }
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("Taxonomy API returned no municipalities");
        }
        municipalities = loaded;
        return loaded;
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
