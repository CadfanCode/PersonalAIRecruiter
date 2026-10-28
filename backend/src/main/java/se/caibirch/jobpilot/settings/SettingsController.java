package se.caibirch.jobpilot.settings;

import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import se.caibirch.jobpilot.claude.ClaudeConcurrency;
import se.caibirch.jobpilot.common.ApiExceptions.NotFoundException;
import se.caibirch.jobpilot.documents.StyleRuleRepository;
import se.caibirch.jobpilot.pipeline.PipelineService;
import se.caibirch.jobpilot.documents.StyleRuleRepository.Phrase;
import se.caibirch.jobpilot.documents.StyleRuleRepository.Rule;

/**
 * The editable parts of Settings (SPEC 8.4, 12.7): writing rules and banned phrases. Rule texts guide the
 * writing prompts; the validators always enforce dashes, new-graduate wording and citations regardless.
 * Claude CLI path and model are still configured with environment variables.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    public record RuleInput(String ruleText, Boolean enabled) {
    }

    public record PhraseInput(String language, String phrase) {
    }

    public record EnabledInput(boolean enabled) {
    }

    private static final Set<String> LANGUAGES = Set.of("en", "sv");

    public record Performance(int claudeConcurrency, int min, int max) {
    }

    public record PerformanceInput(Integer claudeConcurrency) {
    }

    public record SearchSettings(boolean careerPages) {
    }

    private final StyleRuleRepository repo;
    private final ClaudeConcurrency concurrency;
    private final AppSettings settings;

    public SettingsController(StyleRuleRepository repo, ClaudeConcurrency concurrency, AppSettings settings) {
        this.repo = repo;
        this.concurrency = concurrency;
        this.settings = settings;
    }

    /** Search options that are not asked for on every run. Platsbanken is always searched; career pages are opt-in. */
    @GetMapping("/search")
    public SearchSettings search() {
        return new SearchSettings(settings.getBoolean(PipelineService.CAREER_PAGES_KEY, false));
    }

    @PutMapping("/search")
    public SearchSettings updateSearch(@RequestBody SearchSettings in) {
        settings.put(PipelineService.CAREER_PAGES_KEY, in.careerPages());
        return search();
    }

    /** How many model calls run at once: more is faster, but your Claude plan's rate limits are the real ceiling. */
    @GetMapping("/performance")
    public Performance performance() {
        return new Performance(concurrency.limit(), ClaudeConcurrency.MIN, ClaudeConcurrency.MAX);
    }

    @PutMapping("/performance")
    public Performance updatePerformance(@RequestBody PerformanceInput in) {
        if (in.claudeConcurrency() == null || in.claudeConcurrency() < ClaudeConcurrency.MIN || in.claudeConcurrency() > ClaudeConcurrency.MAX) {
            throw new IllegalArgumentException("claudeConcurrency must be between " + ClaudeConcurrency.MIN + " and " + ClaudeConcurrency.MAX);
        }
        concurrency.setLimit(in.claudeConcurrency());
        return performance();
    }

    @GetMapping("/style-rules")
    public List<Rule> rules() {
        return repo.rules();
    }

    @PutMapping("/style-rules/{id}")
    @Transactional
    public List<Rule> updateRule(@PathVariable long id, @RequestBody RuleInput in) {
        if (in.ruleText() == null || in.ruleText().isBlank()) {
            throw new IllegalArgumentException("ruleText is required");
        }
        Rule current = repo.rules().stream().filter(r -> r.id() == id).findFirst()
                .orElseThrow(() -> new NotFoundException("No style rule " + id));
        repo.updateRule(id, in.ruleText().trim(), in.enabled() == null ? current.enabled() : in.enabled());
        return repo.rules();
    }

    @GetMapping("/banned-phrases")
    public List<Phrase> phrases() {
        return repo.phrases();
    }

    @PostMapping("/banned-phrases")
    @ResponseStatus(HttpStatus.CREATED)
    public Phrase addPhrase(@RequestBody PhraseInput in) {
        if (in.language() == null || !LANGUAGES.contains(in.language())) {
            throw new IllegalArgumentException("language must be en or sv");
        }
        if (in.phrase() == null || in.phrase().isBlank()) {
            throw new IllegalArgumentException("phrase is required");
        }
        return repo.addPhrase(in.language(), in.phrase().trim());
    }

    @PutMapping("/banned-phrases/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setEnabled(@PathVariable long id, @RequestBody EnabledInput in) {
        if (!repo.setPhraseEnabled(id, in.enabled())) {
            throw new NotFoundException("No banned phrase " + id);
        }
    }

    @DeleteMapping("/banned-phrases/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        if (!repo.deletePhrase(id)) {
            throw new NotFoundException("No banned phrase " + id);
        }
    }
}
