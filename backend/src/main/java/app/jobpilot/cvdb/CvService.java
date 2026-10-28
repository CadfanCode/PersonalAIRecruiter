package app.jobpilot.cvdb;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.jobpilot.common.ApiExceptions.NotFoundException;
import app.jobpilot.cvdb.CvModel.*;

/** Validation and transactions around {@link CvRepository}. */
@Service
public class CvService {

    /** "YYYY-MM", or "YYYY" when the month is not known. */
    static final Pattern YM = Pattern.compile("^\\d{4}(-(0[1-9]|1[0-2]))?$");

    private final CvRepository repo;

    public CvService(CvRepository repo) {
        this.repo = repo;
    }

    public Profile profile() {
        return repo.findProfile().orElse(new Profile(null, "", null, null, null, null, null, null, null, null,
                null, List.of()));
    }

    @Transactional
    public Profile saveProfile(Profile p) {
        require(p.fullName() != null && !p.fullName().isBlank(), "fullName is required");
        return repo.saveProfile(p);
    }

    public List<Entry> entries() {
        return repo.findAllEntries();
    }

    public Entry entry(long id) {
        return repo.findEntry(id).orElseThrow(() -> new NotFoundException("No CV entry " + id));
    }

    @Transactional
    public Entry createEntry(EntryInput in) {
        validate(in);
        return entry(repo.insertEntry(in));
    }

    /** Creates an entry together with its facts, in the caller's transaction. Used by seed and import. */
    @Transactional
    public Entry createEntry(EntryInput in, List<FactInput> facts) {
        validate(in);
        long id = repo.insertEntry(in);
        for (FactInput f : facts) {
            validate(f);
            repo.insertFact(id, f);
        }
        return entry(id);
    }

    @Transactional
    public Entry updateEntry(long id, EntryInput in) {
        validate(in);
        if (!repo.updateEntry(id, in)) {
            throw new NotFoundException("No CV entry " + id);
        }
        return entry(id);
    }

    @Transactional
    public void deleteEntry(long id) {
        if (!repo.deleteEntry(id)) {
            throw new NotFoundException("No CV entry " + id);
        }
    }

    @Transactional
    public Fact addFact(long entryId, FactInput in) {
        validate(in);
        if (!repo.entryExists(entryId)) {
            throw new NotFoundException("No CV entry " + entryId);
        }
        return fact(repo.insertFact(entryId, in));
    }

    @Transactional
    public Fact updateFact(long id, FactInput in) {
        validate(in);
        if (!repo.updateFact(id, in)) {
            throw new NotFoundException("No CV fact " + id);
        }
        return fact(id);
    }

    @Transactional
    public void deleteFact(long id) {
        if (!repo.deleteFact(id)) {
            throw new NotFoundException("No CV fact " + id);
        }
    }

    private Fact fact(long id) {
        return repo.findFact(id).orElseThrow(() -> new NotFoundException("No CV fact " + id));
    }

    public List<Tag> tags() {
        return repo.findAllTags();
    }

    @Transactional
    public Tag createTag(String name) {
        require(name != null && !name.isBlank(), "name is required");
        long id = repo.upsertTag(name.trim());
        return new Tag(id, name.trim());
    }

    public List<Skill> skills() {
        return repo.findAllSkills();
    }

    @Transactional
    public Skill saveSkill(SkillInput in) {
        require(in.name() != null && !in.name().isBlank(), "name is required");
        requireYm(in.lastUsedYm(), "lastUsedYm");
        return repo.upsertSkill(in);
    }

    @Transactional
    public void addSkillIfAbsent(String name, String category) {
        require(name != null && !name.isBlank(), "skill name is required");
        repo.insertSkillIfAbsent(name.trim(), category);
    }

    @Transactional
    public void deleteSkill(long id) {
        if (!repo.deleteSkill(id)) {
            throw new NotFoundException("No skill " + id);
        }
    }

    public List<KnownGap> gaps() {
        return repo.findGaps();
    }

    @Transactional
    public List<KnownGap> replaceGaps(List<String> texts) {
        repo.replaceGaps(texts.stream().map(String::trim).filter(t -> !t.isEmpty()).toList());
        return repo.findGaps();
    }

    public List<Answer> answers() {
        return repo.findAnswers();
    }

    @Transactional
    public void saveAnswer(String question, String answer) {
        require(question != null && !question.isBlank() && answer != null && !answer.isBlank(),
                "question and answer are required");
        repo.upsertAnswer(question.trim(), answer.trim());
    }

    private static void validate(EntryInput in) {
        require(in.kind() != null, "kind is required");
        require(in.title() != null && !in.title().isBlank(), "title is required");
        requireYm(in.startYm(), "startYm");
        requireYm(in.endYm(), "endYm");
    }

    private static void validate(FactInput in) {
        require((in.textEn() != null && !in.textEn().isBlank()) || (in.textSv() != null && !in.textSv().isBlank()),
                "a fact needs textEn or textSv");
    }

    private static void requireYm(String value, String field) {
        require(value == null || YM.matcher(value).matches(), field + " must be YYYY-MM or YYYY");
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
