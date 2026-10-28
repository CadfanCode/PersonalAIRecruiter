package app.jobpilot.claude;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** The Gemini models the saved key can call, for the model selector in Settings. Aliases such as gemini-flash-latest come first. */
@Component
public class GeminiModels {

    public record ModelOption(String id, String label) {
    }

    private static final Logger log = LoggerFactory.getLogger(GeminiModels.class);
    /** Text models only: no speech, image, robotics, computer-use or embedding variants. */
    private static final Pattern EXCLUDED = Pattern.compile("(?i)tts|image|robotics|computer-use|customtools|transcribe|omni|banana|embedding|live|audio|nano");

    private final GeminiProperties gemini;
    private final ApiKeyVault vault;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GeminiModels(GeminiProperties gemini, ApiKeyVault vault, ObjectMapper mapper) {
        this.gemini = gemini;
        this.vault = vault;
        this.mapper = mapper;
    }

    public List<ModelOption> list() {
        List<ModelOption> out = new ArrayList<>();
        out.add(new ModelOption("gemini-flash-latest", "Gemini Flash (latest, fast)"));
        out.add(new ModelOption("gemini-pro-latest", "Gemini Pro (latest, most capable)"));
        try {
            String key = vault.get().orElse(null);
            if (key == null) {
                return out;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(gemini.baseUrl().replaceAll("/+$", "") + "/v1beta/models?pageSize=200"))
                    .timeout(Duration.ofSeconds(15)).header("x-goog-api-key", key).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return out;
            }
            for (JsonNode m : mapper.readTree(response.body()).path("models")) {
                String id = m.path("name").asText("").replaceFirst("^models/", "");
                boolean generates = false;
                for (JsonNode method : m.path("supportedGenerationMethods")) {
                    generates |= "generateContent".equals(method.asText());
                }
                if (generates && id.startsWith("gemini-") && !EXCLUDED.matcher(id).find() && out.stream().noneMatch(o -> o.id().equals(id))) {
                    out.add(new ModelOption(id, m.path("displayName").asText(id)));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.info("Could not list Gemini models: {}", e.getClass().getSimpleName());
        }
        return out;
    }
}
