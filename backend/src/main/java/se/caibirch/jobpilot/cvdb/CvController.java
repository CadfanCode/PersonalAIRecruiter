package se.caibirch.jobpilot.cvdb;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import se.caibirch.jobpilot.cvdb.CvModel.*;

@RestController
@RequestMapping("/api")
public class CvController {

    public record TagInput(String name) {
    }

    public record GapsInput(List<String> gaps) {
    }

    public record AnswerInput(String question, String answer) {
    }

    private final CvService service;
    private final CvSeedImporter seedImporter;

    public CvController(CvService service, CvSeedImporter seedImporter) {
        this.service = service;
        this.seedImporter = seedImporter;
    }

    @GetMapping("/profile")
    public Profile profile() {
        return service.profile();
    }

    @PutMapping("/profile")
    public Profile saveProfile(@RequestBody Profile profile) {
        return service.saveProfile(profile);
    }

    @GetMapping("/cv/entries")
    public List<Entry> entries() {
        return service.entries();
    }

    @PostMapping("/cv/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public Entry createEntry(@RequestBody EntryInput in) {
        return service.createEntry(in);
    }

    @GetMapping("/cv/entries/{id}")
    public Entry entry(@PathVariable long id) {
        return service.entry(id);
    }

    @PutMapping("/cv/entries/{id}")
    public Entry updateEntry(@PathVariable long id, @RequestBody EntryInput in) {
        return service.updateEntry(id, in);
    }

    @DeleteMapping("/cv/entries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEntry(@PathVariable long id) {
        service.deleteEntry(id);
    }

    @PostMapping("/cv/entries/{id}/facts")
    @ResponseStatus(HttpStatus.CREATED)
    public Fact addFact(@PathVariable long id, @RequestBody FactInput in) {
        return service.addFact(id, in);
    }

    @PutMapping("/cv/facts/{id}")
    public Fact updateFact(@PathVariable long id, @RequestBody FactInput in) {
        return service.updateFact(id, in);
    }

    @DeleteMapping("/cv/facts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFact(@PathVariable long id) {
        service.deleteFact(id);
    }

    @GetMapping("/cv/tags")
    public List<Tag> tags() {
        return service.tags();
    }

    @PostMapping("/cv/tags")
    @ResponseStatus(HttpStatus.CREATED)
    public Tag createTag(@RequestBody TagInput in) {
        return service.createTag(in.name());
    }

    @GetMapping("/cv/skills")
    public List<Skill> skills() {
        return service.skills();
    }

    @PostMapping("/cv/skills")
    @ResponseStatus(HttpStatus.CREATED)
    public Skill saveSkill(@RequestBody SkillInput in) {
        return service.saveSkill(in);
    }

    @DeleteMapping("/cv/skills/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSkill(@PathVariable long id) {
        service.deleteSkill(id);
    }

    @GetMapping("/cv/gaps")
    public List<KnownGap> gaps() {
        return service.gaps();
    }

    @PutMapping("/cv/gaps")
    public List<KnownGap> replaceGaps(@RequestBody GapsInput in) {
        return service.replaceGaps(in.gaps());
    }

    /** Loads seed/cv_database_seed.json (sent as the request body). 409 when the database is not empty. */
    @PostMapping("/cv/seed")
    @ResponseStatus(HttpStatus.CREATED)
    public CvSeedImporter.Result importSeed(@RequestBody String json) {
        return seedImporter.importSeed(json);
    }

    @GetMapping("/cv/answers")
    public List<Answer> answers() {
        return service.answers();
    }

    @PutMapping("/cv/answers")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveAnswer(@RequestBody AnswerInput in) {
        service.saveAnswer(in.question(), in.answer());
    }
}
