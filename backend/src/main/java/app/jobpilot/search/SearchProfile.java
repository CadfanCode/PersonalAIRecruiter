package app.jobpilot.search;

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

    /**
     * The profile for one discovered role: the role's own fields (titles, queries, skills, yrkesroll...) on top of the run's
     * base profile, which carries the user's locations, age limit, employment types and languages.
     */
    public SearchProfile withRole(RoleFields role, List<String> knownTags) {
        List<String> tags = role.relevantTags() == null ? List.<String>of()
                : role.relevantTags().stream().filter(knownTags::contains).toList();
        return new SearchProfile(role.roleSummary(), role.titles(), role.synonyms(), role.skillsWanted(), role.seniority(),
                role.acceptExperienceUpToYears(), locations, remoteOk, languages, maxAgeDays, employmentTypes,
                role.exclusions(), role.yrkesrollForAktivitetsrapport(), tags, role.jobtechQueries());
    }

    /** What Claude proposes per role; the rest of a SearchProfile comes from the user's own constraints. */
    public record RoleFields(String roleSummary, List<String> titles, List<String> synonyms, List<String> skillsWanted,
                             String seniority, int acceptExperienceUpToYears, List<String> exclusions,
                             String yrkesrollForAktivitetsrapport, List<String> relevantTags, List<String> jobtechQueries) {
    }

    public SearchProfile withMaxAgeDays(int days) {
        return new SearchProfile(roleSummary, titles, synonyms, skillsWanted, seniority, acceptExperienceUpToYears,
                locations, remoteOk, languages, days, employmentTypes, exclusions, yrkesrollForAktivitetsrapport,
                relevantTags, jobtechQueries);
    }
}
