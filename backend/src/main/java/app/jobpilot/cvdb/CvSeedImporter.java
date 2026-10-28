package app.jobpilot.cvdb;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.jobpilot.common.ApiExceptions.ConflictException;
import app.jobpilot.cvdb.CvModel.*;

/**
 * Loads {@code seed/cv_database_seed.json}. Parsing is strict: a field the importer does not know
 * fails the import instead of being dropped silently. Runs in one transaction and refuses to touch
 * a database that already has a profile or entries.
 */
@Service
public class CvSeedImporter {

    public record SeedProfile(String fullName, String headline, String email, String phone, String phoneInternational,
                              String city, String website, String github, String linkedin,
                              List<Language> languages, String workAuthorisation) {
    }

    public record SeedFact(String en, String sv, String evidence, List<String> tags) {
    }

    public record SeedEntry(String key, Kind kind, String title, String titleEn, String organisation, String location,
                            String start, String end, Boolean ongoing, String note, String years, List<String> tags,
                            List<SeedFact> facts) {
    }

    public record SeedSkill(String name, String category) {
    }

    public record SeedAnswer(String question, String answer) {
    }

    public record Seed(@JsonProperty("_comment") String comment, SeedProfile profile, List<String> tags,
                       List<SeedEntry> entries, List<SeedSkill> skills, List<String> knownGaps,
                       List<SeedAnswer> answersMemory) {
    }

    public record Result(int entries, int facts, int tags, int skills, int knownGaps, int answers) {
    }

    private static final Pattern YEARS = Pattern.compile("^(\\d{4})-(\\d{4})$");

    private final ObjectMapper strict = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final CvRepository repo;
    private final CvService service;

    public CvSeedImporter(CvRepository repo, CvService service) {
        this.repo = repo;
        this.service = service;
    }

    public Seed parse(String json) {
        try {
            return strict.readValue(json, Seed.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid seed file: " + e.getOriginalMessage(), e);
        }
    }

    @Transactional
    public Result importSeed(String json) {
        Seed seed = parse(json);
        if (!repo.isEmpty()) {
            throw new ConflictException("The CV database is not empty; refusing to import the seed over it");
        }
        SeedProfile p = seed.profile();
        service.saveProfile(new Profile(null, p.fullName(), p.headline(), p.email(), p.phone(),
                p.phoneInternational(), p.city(), p.website(), p.github(), p.linkedin(), p.workAuthorisation(),
                p.languages()));

        seed.tags().forEach(repo::upsertTag);
        for (SeedSkill s : seed.skills()) {
            service.addSkillIfAbsent(s.name(), s.category());
        }

        int facts = 0;
        int order = 0;
        for (SeedEntry e : seed.entries()) {
            String[] range = range(e);
            EntryInput in = new EntryInput(e.kind(), e.title(), e.titleEn(), e.organisation(), e.location(),
                    range[0], range[1], e.ongoing(), null, e.note(), order++, false, e.tags());
            List<FactInput> factInputs = new ArrayList<>();
            int factOrder = 0;
            for (SeedFact f : e.facts()) {
                factInputs.add(new FactInput(f.en(), f.sv(), f.evidence(), null, factOrder++, f.tags()));
            }
            service.createEntry(in, factInputs);
            facts += factInputs.size();
        }
        service.replaceGaps(seed.knownGaps());
        seed.answersMemory().forEach(a -> service.saveAnswer(a.question(), a.answer()));

        return new Result(seed.entries().size(), facts, repo.findAllTags().size(), seed.skills().size(),
                seed.knownGaps().size(), seed.answersMemory().size());
    }

    /** {@code years: "2024-2026"} becomes year-only dates; the months stay unknown rather than invented. */
    private static String[] range(SeedEntry e) {
        if (e.years() == null) {
            return new String[] {e.start(), e.end()};
        }
        Matcher m = YEARS.matcher(e.years());
        if (!m.matches() || e.start() != null || e.end() != null) {
            throw new IllegalArgumentException("Entry '" + e.key() + "': cannot read years '" + e.years() + "'");
        }
        return new String[] {m.group(1), m.group(2)};
    }
}
