package app.jobpilot.onboarding;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import app.jobpilot.claude.ProviderSettings;
import app.jobpilot.common.ApiExceptions.ConflictException;
import app.jobpilot.country.CountryRegistry;
import app.jobpilot.cvdb.CvModel.Entry;
import app.jobpilot.cvdb.CvService;
import app.jobpilot.settings.AppSettings;

/**
 * First-run setup: four steps (CV, Claude, country, search mode). This only reports where the user is and remembers the answers;
 * each step's real work lives in its own feature (CV import, connection, country, runs). Returning users skip all of it.
 */
@Service
public class OnboardingService {

    public record CvStep(boolean present, int entries, int facts, int skills) {
    }

    public record ClaudeStep(boolean chosen, String provider, boolean works) {
    }

    public record CountryStep(boolean chosen, String code) {
    }

    public record ModeStep(boolean chosen, String mode) {
    }

    /** {@code nextStep} is the first step not done yet: CV, CLAUDE, COUNTRY, MODE, or null when all four are. */
    public record State(CvStep cv, ClaudeStep claude, CountryStep country, ModeStep mode, boolean complete, String nextStep) {
    }

    static final String COMPLETE_KEY = "onboarding.complete";
    static final String MODE_KEY = "search.mode";
    static final Set<String> MODES = Set.of("DISCOVER", "PROMPT");

    private final CvService cv;
    private final ProviderSettings providers;
    private final CountryRegistry countries;
    private final AppSettings settings;

    public OnboardingService(CvService cv, ProviderSettings providers, CountryRegistry countries, AppSettings settings) {
        this.cv = cv;
        this.providers = providers;
        this.countries = countries;
        this.settings = settings;
    }

    public State state() {
        List<Entry> entries = cv.entries();
        int facts = entries.stream().mapToInt(e -> e.facts().size()).sum();
        CvStep cvStep = new CvStep(!entries.isEmpty(), entries.size(), facts, cv.skills().size());
        ClaudeStep claude = new ClaudeStep(providers.chosen(), providers.selectedId(), providers.selectedWorks());
        CountryStep country = new CountryStep(countries.isChosen(), countries.selected().code());
        String mode = settings.get(MODE_KEY).filter(MODES::contains).orElse(null);
        ModeStep modeStep = new ModeStep(mode != null, mode == null ? "PROMPT" : mode);
        String next = !cvStep.present() ? "CV" : !claude.works() ? "CLAUDE" : !country.chosen() ? "COUNTRY" : !modeStep.chosen() ? "MODE" : null;
        boolean complete = settings.getBoolean(COMPLETE_KEY, false) && next == null;
        return new State(cvStep, claude, country, modeStep, complete, next);
    }

    public State chooseMode(String mode) {
        if (mode == null || !MODES.contains(mode)) {
            throw new IllegalArgumentException("mode must be DISCOVER or PROMPT");
        }
        settings.put(MODE_KEY, mode);
        return state();
    }

    /** Ends onboarding. Refused while a step is still open, so a half-set-up app never lands in the pipeline. */
    public State complete() {
        State s = state();
        if (s.nextStep() != null) {
            throw new ConflictException("Setup is not finished: the " + s.nextStep().toLowerCase() + " step is still open");
        }
        settings.put(COMPLETE_KEY, true);
        return state();
    }

    /** "Run setup again" from Settings: shows onboarding on the next start without deleting anything the user has. */
    public State reopen() {
        settings.put(COMPLETE_KEY, false);
        return state();
    }
}
