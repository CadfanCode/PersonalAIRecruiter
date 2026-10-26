package se.caibirch.jobpilot.search;

import java.util.List;

/** The single source of intent for a run (SPEC 5.4). Immutable: an edit creates a new stored profile. */
public record SearchProfile(String roleSummary, List<String> titles, List<String> synonyms,
                            List<String> skillsWanted, String seniority, int acceptExperienceUpToYears,
                            List<Location> locations, boolean remoteOk, List<String> languages, int maxAgeDays,
                            List<String> employmentTypes, List<String> exclusions,
                            String yrkesrollForAktivitetsrapport, List<String> relevantTags,
                            List<String> jobtechQueries) {

    public record Location(String name, String municipality, int radiusKm) {
    }

    public SearchProfile withMaxAgeDays(int days) {
        return new SearchProfile(roleSummary, titles, synonyms, skillsWanted, seniority, acceptExperienceUpToYears,
                locations, remoteOk, languages, days, employmentTypes, exclusions, yrkesrollForAktivitetsrapport,
                relevantTags, jobtechQueries);
    }
}
