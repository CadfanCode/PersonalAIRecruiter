package app.jobpilot.discovery;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import app.jobpilot.cvdb.CvModel.Tag;
import app.jobpilot.cvdb.CvService;
import app.jobpilot.ranking.CvDigestService;
import app.jobpilot.search.SearchProfile;
import app.jobpilot.search.SearchProfile.RoleFields;
import app.jobpilot.search.SearchProfileRepository;
import app.jobpilot.settings.JobPilotProperties;

/**
 * Discover roles: asks Claude for role types the candidate would match, from the WHOLE CV database, keeps them per run, and
 * turns the ones the user chose (or typed) into one SearchProfile each. Nothing here searches or scores.
 */
@Service
public class RoleDiscoveryService {

    public static final int SUGGESTIONS = 12;
    public static final int AGGRESSIVE_SUGGESTIONS = 24;

    /** Claude's answer for {@code suggestRoles}. */
    public record Suggested(List<SuggestedRole> roles) {
    }

    public record SuggestedRole(String title, String category, int matchScore, String reasoning, String gaps, RoleFields profile) {
    }

    /** Claude's answer for {@code profileRoles}: search fields for roles the user added or renamed. */
    public record RoleProfiles(List<ProfiledRole> roles) {
    }

    public record ProfiledRole(String title, RoleFields profile) {
    }

    /** A role as the UI shows it, with how many ads it found once the run has searched. */
    public record RoleView(long id, String title, String category, Integer matchScore, String reasoning, String gaps,
                           boolean selected, boolean userAdded, boolean searched, int jobCount) {
    }

    /** What the user sends back: the complete list of roles as they want it. A null id is a role the user typed in. */
    public record RoleEdit(Long id, String title, Boolean selected) {
    }

    /** A selected role with the profile that searches, ranks and tailors for it. */
    public record ResolvedRole(long id, String title, long searchProfileId, SearchProfile profile) {
    }

    private final ClaudeRunner claude;
    private final CvService cv;
    private final CvDigestService digests;
    private final RoleRepository roles;
    private final SearchProfileRepository profiles;
    private final JobPilotProperties props;
    private final ObjectMapper mapper;

    private final CountryRegistry countries;

    public RoleDiscoveryService(ClaudeRunner claude, CvService cv, CvDigestService digests, RoleRepository roles,
                                SearchProfileRepository profiles, JobPilotProperties props, ObjectMapper mapper,
                                CountryRegistry countries) {
        this.countries = countries;
        this.claude = claude;
        this.cv = cv;
        this.digests = digests;
        this.roles = roles;
        this.profiles = profiles;
        this.props = props;
        this.mapper = mapper;
    }

    /** One Claude call over the whole CV digest; stores the roles. Strong matches start ticked, the user decides the rest. */
    public List<RoleRepository.Row> suggest(long runId, String context, boolean aggressive, LocalDate today) {
        int count = aggressive ? AGGRESSIVE_SUGGESTIONS : SUGGESTIONS;
        String breadth = aggressive
                ? "Be generous and think widely: include roles with a moderate match (40 or more) when there is a real argument for them, and also unusual roles the candidate may not have considered."
                : "Only include roles with a matchScore of 55 or more.";
        Suggested answer = claude.run(ClaudeTask.longRunning("suggestRoles", ClaudeResources.prompt("suggestRoles", Map.of(
                "today", today.toString(), "market", countries.selected().market(), "context", context == null || context.isBlank() ? "(nothing)" : context,
                "cv", digests.build().json(), "tags", tagList(), "count", String.valueOf(count), "breadth", breadth)),
                ClaudeResources.schema("suggestRoles"), Suggested.class));
        int preselect = aggressive ? 45 : 60;
        Set<String> seen = new LinkedHashSet<>();
        int order = roles.nextSortOrder(runId);
        for (SuggestedRole r : answer.roles()) {
            if (r.title() == null || r.title().isBlank() || !seen.add(r.title().strip().toLowerCase(Locale.ROOT))) {
                continue; // a duplicate role type adds nothing to choose between
            }
            roles.insert(runId, r.title().strip(), r.category(), r.matchScore(), r.reasoning(), r.gaps(), r.matchScore() >= preselect,
                    false, json(r.profile()), order++);
        }
        if (seen.isEmpty()) {
            throw new IllegalStateException("The AI suggested no roles");
        }
        return roles.list(runId);
    }

    public List<RoleRepository.Row> list(long runId) {
        return roles.list(runId);
    }

    public List<RoleView> views(long runId) {
        return roles.list(runId).stream().map(r -> new RoleView(r.id(), r.title(), r.category(), r.matchScore(), r.reasoning(),
                r.gaps(), r.selected(), r.userAdded(), r.searchProfileId() != null, r.jobCount())).toList();
    }

    /** Applies the user's list: selection, renames, roles they typed in. Roles they added and then removed are deleted. */
    public List<RoleRepository.Row> saveSelection(long runId, List<RoleEdit> edits) {
        List<RoleRepository.Row> existing = roles.list(runId);
        Map<Long, RoleRepository.Row> byId = new LinkedHashMap<>();
        existing.forEach(r -> byId.put(r.id(), r));
        Set<Long> kept = new LinkedHashSet<>();
        Set<String> titles = new LinkedHashSet<>();
        int order = roles.nextSortOrder(runId);
        for (RoleEdit e : edits) {
            String title = e.title() == null ? null : e.title().strip();
            boolean selected = Boolean.TRUE.equals(e.selected());
            if (e.id() == null) {
                if (title == null || title.isEmpty() || !titles.add(title.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                roles.insert(runId, title, "CUSTOM", null, null, null, selected, true, null, order++);
                continue;
            }
            RoleRepository.Row row = byId.get(e.id());
            if (row == null) {
                throw new NotFoundException("No role " + e.id() + " in run " + runId);
            }
            kept.add(row.id());
            if (title != null && !title.isEmpty()) {
                titles.add(title.toLowerCase(Locale.ROOT));
                if (!title.equals(row.title())) {
                    roles.rename(row.id(), title);
                }
            }
            roles.setSelected(row.id(), selected);
        }
        for (RoleRepository.Row row : existing) {
            if (row.userAdded() && !kept.contains(row.id())) {
                roles.delete(row.id());
            }
        }
        return roles.list(runId);
    }

    public List<RoleRepository.Row> selected(long runId) {
        return roles.list(runId).stream().filter(RoleRepository.Row::selected).toList();
    }

    /**
     * One SearchProfile per selected role: Claude's role fields on top of the run's base profile (the user's locations, age limit
     * and employment types). Roles the user typed or renamed get their fields from one batched Claude call. Profiles are validated
     * against the same schema as every other profile, because the yrkesroll in them is typed into Arbetsförmedlingen's form.
     */
    public List<ResolvedRole> resolve(long runId, SearchProfile base, LocalDate today) {
        List<RoleRepository.Row> chosen = selected(runId);
        List<RoleRepository.Row> missing = chosen.stream().filter(r -> r.profileJson() == null).toList();
        if (!missing.isEmpty()) {
            RoleProfiles built = claude.run(ClaudeTask.of("profileRoles", ClaudeResources.prompt("profileRoles", Map.of(
                    "today", today.toString(), "market", countries.selected().market(), "roles", json(missing.stream().map(RoleRepository.Row::title).toList()),
                    "candidate", json(Map.of("headline", java.util.Objects.toString(cv.profile().headline(), ""),
                            "city", java.util.Objects.toString(cv.profile().city(), ""))),
                    "tags", tagList())),
                    ClaudeResources.schema("roleProfiles"), RoleProfiles.class));
            if (built.roles().size() != missing.size()) {
                throw new IllegalStateException("The AI returned " + built.roles().size() + " role profiles for " + missing.size() + " roles");
            }
            for (int i = 0; i < missing.size(); i++) {
                roles.setProfileJson(missing.get(i).id(), json(built.roles().get(i).profile()));
            }
            chosen = selected(runId);
        }
        List<String> knownTags = cv.tags().stream().map(Tag::name).toList();
        List<ResolvedRole> out = new ArrayList<>();
        for (RoleRepository.Row row : chosen) {
            SearchProfile profile = base.withRole(fields(row), knownTags);
            Long id = row.searchProfileId();
            if (id == null) {
                JsonNode tree = mapper.valueToTree(profile);
                List<String> problems = JsonSchemas.validate("searchProfile", tree);
                if (!problems.isEmpty()) {
                    throw new IllegalStateException("The search fields for '" + row.title() + "' are not valid: " + String.join("; ", problems));
                }
                id = profiles.insert(row.title(), profile);
                roles.setSearchProfile(row.id(), id);
            }
            out.add(new ResolvedRole(row.id(), row.title(), id, profile));
        }
        return out;
    }

    private RoleFields fields(RoleRepository.Row row) {
        try {
            return mapper.readValue(row.profileJson(), RoleFields.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt search fields for role " + row.title(), e);
        }
    }

    private String tagList() {
        String tags = cv.tags().stream().map(Tag::name).collect(Collectors.joining(", "));
        return tags.isEmpty() ? "(none)" : tags;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
