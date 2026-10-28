package se.caibirch.jobpilot.country;

import java.util.List;

import org.springframework.stereotype.Service;

import se.caibirch.jobpilot.common.ApiExceptions.ConflictException;
import se.caibirch.jobpilot.settings.AppSettings;

/** The countries JobPilot knows, which of them can be searched, and the one the user chose in onboarding. */
@Service
public class CountryRegistry {

    static final String KEY = "country";
    static final String DEFAULT = "SE";

    private record Known(String code, String name, String jobBoard, String market, boolean activityReport) {
    }

    /** Every country shown in the picker. Only those with a {@link JobSource} bean are available. */
    private static final List<Known> KNOWN = List.of(
            new Known("SE", "Sweden", "Platsbanken", "the Swedish job market (Platsbanken, run by Arbetsförmedlingen)", true),
            new Known("NO", "Norway", "Finn.no", "the Norwegian job market", false),
            new Known("DK", "Denmark", "Jobnet", "the Danish job market", false),
            new Known("FI", "Finland", "Työmarkkinatori", "the Finnish job market", false),
            new Known("DE", "Germany", "Bundesagentur für Arbeit", "the German job market", false),
            new Known("GB", "United Kingdom", "Find a job", "the UK job market", false));

    private final List<JobSource> sources;
    private final AppSettings settings;

    public CountryRegistry(List<JobSource> sources, AppSettings settings) {
        this.sources = sources;
        this.settings = settings;
    }

    public List<Country> countries() {
        return KNOWN.stream().map(k -> new Country(k.code(), k.name(), k.jobBoard(), k.market(), k.activityReport(), sourceFor(k.code()) != null)).toList();
    }

    public Country selected() {
        String code = settings.get(KEY, DEFAULT);
        return countries().stream().filter(c -> c.code().equals(code) && c.available()).findFirst()
                .orElseGet(() -> countries().stream().filter(c -> c.code().equals(DEFAULT)).findFirst().orElseThrow());
    }

    public boolean isChosen() {
        return settings.get(KEY).isPresent();
    }

    public Country select(String code) {
        Country country = countries().stream().filter(c -> c.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown country " + code));
        if (!country.available()) {
            throw new ConflictException(country.name() + " is coming soon: its job sources are not built yet");
        }
        settings.put(KEY, code);
        return country;
    }

    /** The job source of the chosen country. */
    public JobSource source() {
        return sourceFor(selected().code());
    }

    private JobSource sourceFor(String code) {
        return sources.stream().filter(s -> s.countryCode().equals(code)).findFirst().orElse(null);
    }
}
