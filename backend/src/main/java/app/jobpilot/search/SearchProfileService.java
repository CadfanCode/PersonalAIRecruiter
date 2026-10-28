package app.jobpilot.search;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import app.jobpilot.claude.ClaudeResources;
import app.jobpilot.claude.ClaudeRunner;
import app.jobpilot.country.CountryRegistry;
import app.jobpilot.claude.ClaudeTask;
import app.jobpilot.claude.JsonSchemas;
import app.jobpilot.common.ApiExceptions.NotFoundException;
import app.jobpilot.cvdb.CvModel.Profile;
import app.jobpilot.cvdb.CvModel.Tag;
import app.jobpilot.cvdb.CvService;
import app.jobpilot.pipeline.RunOptions;
import app.jobpilot.settings.JobPilotProperties;

/** Turns the starting prompt into a stored {@link SearchProfile} and validates user edits of one. */
@Service
public class SearchProfileService {

    private final ClaudeRunner claude;
    private final CvService cv;
    private final SearchProfileRepository repo;
    private final JobPilotProperties props;
    private final ObjectMapper mapper;

    private final CountryRegistry countries;

    public SearchProfileService(ClaudeRunner claude, CvService cv, SearchProfileRepository repo,
                                JobPilotProperties props, ObjectMapper mapper, CountryRegistry countries) {
        this.countries = countries;
        this.claude = claude;
        this.cv = cv;
        this.repo = repo;
        this.props = props;
        this.mapper = mapper;
    }

    /** Asks Claude for a profile, applies the user's explicit constraints and stores it. Returns the profile id. */
    public long build(String prompt, RunOptions options, LocalDate today) {
        Profile p = cv.profile();
        Map<String, Object> candidateMap = new java.util.LinkedHashMap<>();
        candidateMap.put("headline", p.headline());
        candidateMap.put("city", p.city());
        candidateMap.put("languages", p.languages() == null ? List.of() : p.languages());
        String candidate = json(candidateMap);
        String tags = cv.tags().stream().map(Tag::name).collect(Collectors.joining(", "));
        Map<String, Object> constraintMap = new java.util.LinkedHashMap<>();
        constraintMap.put("maxAgeDays", options.maxAgeDays());
        String constraints = json(constraintMap);

        SearchProfile built = claude.run(ClaudeTask.of("buildSearchProfile",
                ClaudeResources.prompt("buildSearchProfile", Map.of(
                        "today", today.toString(), "market", countries.selected().market(), "prompt", prompt, "constraints", constraints,
                        "candidate", candidate, "tags", tags.isEmpty() ? "(none)" : tags)),
                ClaudeResources.schema("searchProfile"), SearchProfile.class));

        SearchProfile profile = options.maxAgeDays() != null ? built.withMaxAgeDays(options.maxAgeDays()) : built;
        return repo.insert(prompt, profile);
    }

    /** A copy of a stored profile with a wider age limit (profiles are immutable: this stores a new row). */
    public long withMaxAgeDays(long id, int days) {
        SearchProfileRepository.Stored stored = get(id);
        return repo.insert(stored.prompt(), stored.profile().withMaxAgeDays(days));
    }

    public SearchProfileRepository.Stored get(long id) {
        return repo.find(id).orElseThrow(() -> new NotFoundException("No search profile " + id));
    }

    /** Validates an edited profile against the same schema Claude's answer must satisfy, then stores a new row. */
    public long saveEdited(String prompt, JsonNode edited) {
        List<String> problems = JsonSchemas.validate("searchProfile", edited);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid search profile: " + String.join("; ", problems));
        }
        try {
            return repo.insert(prompt, mapper.treeToValue(edited, SearchProfile.class));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid search profile: " + e.getOriginalMessage(), e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
