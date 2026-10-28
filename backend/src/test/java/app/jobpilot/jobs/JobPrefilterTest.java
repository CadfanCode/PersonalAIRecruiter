package app.jobpilot.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.jobpilot.search.SearchProfile;
import app.jobpilot.search.SearchProfile.Location;

class JobPrefilterTest {

    static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    static SearchProfile profile(boolean remoteOk, List<String> exclusions, String... municipalities) {
        List<Location> locations = java.util.Arrays.stream(municipalities).map(m -> new Location(m, m, 0)).toList();
        return new SearchProfile("Java developer", List.of("Javautvecklare"), List.of(), List.of("Java"), "graduate", 2,
                locations, remoteOk, List.of("sv", "en"), 7, List.of("heltid"), exclusions,
                "Systemutvecklare/Programmerare", List.of("java"), List.of("java"));
    }

    static NewJob job(String title, String employer, String municipality, Instant published, Instant deadline) {
        return new NewJob("JOBTECH", title + employer, "https://x/" + title, null, title, employer, null, municipality,
                municipality, published, deadline, "sv", "Heltid", "Beskrivning", null, false, null, 1.0, false);
    }

    List<String> keptTitles(JobPrefilter.Result r) {
        return r.kept().stream().map(NewJob::title).toList();
    }

    @Test
    void dropsAdsOlderThanMaxAge() {
        var result = JobPrefilter.apply(List.of(
                job("Fresh", "A", "Stockholm", NOW.minus(Duration.ofDays(6)), null),
                job("Stale", "B", "Stockholm", NOW.minus(Duration.ofDays(8)), null)), profile(false, List.of()), NOW);

        assertThat(keptTitles(result)).containsExactly("Fresh");
        assertThat(result.dropped().get(0).reason()).isEqualTo("older than 7 days");
    }

    @Test
    void dropsAdsWhoseDeadlineHasPassedButKeepsOpenEnded() {
        var result = JobPrefilter.apply(List.of(
                job("Open", "A", "Stockholm", NOW, null),
                job("Later", "B", "Stockholm", NOW, NOW.plus(Duration.ofDays(3))),
                job("Closed", "C", "Stockholm", NOW, NOW.minus(Duration.ofHours(1)))), profile(false, List.of()), NOW);

        assertThat(keptTitles(result)).containsExactly("Open", "Later");
    }

    @Test
    void exclusionsMatchWholeWordsInTitleAndEmployerOnly() {
        var jobs = List.of(
                job("Senior Java Developer", "A", "Stockholm", NOW, null),
                job("Java Developer", "Seniorboendet AB", "Stockholm", NOW, null),
                job("Java Developer", "B", "Stockholm", NOW, null),
                job("Seniority-aware Developer", "C", "Stockholm", NOW, null),
                job("Developer", "D", "Stockholm", NOW, null));
        var withDescription = new NewJob("JOBTECH", "x", null, null, "Java Developer", "E", null, "Stockholm", "Stockholm",
                NOW, null, "en", null, "You work with senior colleagues", null, false, null, 1, false);

        var result = JobPrefilter.apply(java.util.stream.Stream.concat(jobs.stream(), java.util.stream.Stream.of(withDescription)).toList(),
                profile(false, List.of("senior"), "Stockholm"), NOW);

        assertThat(result.dropped()).extracting(d -> d.job().title() + "/" + d.job().employer())
                .containsExactly("Senior Java Developer/A");
        assertThat(result.dropped().get(0).reason()).isEqualTo("excluded term 'senior'");
        assertThat(result.kept()).hasSize(5);
    }

    @Test
    void commuterMunicipalitiesSurviveAnExpandedStockholmProfile() {
        var profile = profile(false, List.of(), "Stockholm", "Solna", "Järfälla", "Nacka");
        var result = JobPrefilter.apply(List.of(
                job("In Solna", "Arbetsförmedlingen", "Solna", NOW, null),
                job("In Järfälla", "Saab", "Järfälla", NOW, null),
                job("In Malmö", "X", "Malmö", NOW, null)), profile, NOW);

        assertThat(keptTitles(result)).containsExactly("In Solna", "In Järfälla");
        assertThat(result.dropped().get(0).reason()).isEqualTo("outside locations (Malmö)");
    }

    @Test
    void summarisesWhyAdsWereDropped() {
        var result = JobPrefilter.apply(List.of(
                job("Senior A", "A", "Stockholm", NOW, null), job("Senior B", "B", "Stockholm", NOW, null),
                job("Closed", "C", "Stockholm", NOW, NOW.minus(Duration.ofHours(1))),
                job("Old", "D", "Stockholm", NOW.minus(Duration.ofDays(30)), null),
                job("Far", "E", "Malmö", NOW, null), job("Fine", "F", "Stockholm", NOW, null)),
                profile(false, List.of("senior"), "Stockholm"), NOW);

        assertThat(result.dropSummary()).isEqualTo("2 excluded term, 1 deadline passed, 1 too old, 1 outside locations");
        assertThat(JobPrefilter.apply(List.of(), profile(false, List.of()), NOW).dropSummary()).isEmpty();
    }

    @Test
    void adWithoutLocationIsKeptBecauseItCannotBeRuledOut() {
        var result = JobPrefilter.apply(List.of(job("Anywhere", "A", null, NOW, null)), profile(false, List.of(), "Stockholm"), NOW);

        assertThat(keptTitles(result)).containsExactly("Anywhere");
    }

    @Test
    void emptyLocationListMeansAllOfSweden() {
        var result = JobPrefilter.apply(List.of(job("Luleå job", "A", "Luleå", NOW, null)), profile(false, List.of()), NOW);

        assertThat(result.kept()).hasSize(1);
    }

    @Test
    void remoteAdsOutsideTheLocationsSurviveOnlyWhenRemoteIsOk() {
        var remote = new NewJob("JOBTECH", "r", null, null, "Remote Java dev", "A", null, "Malmö", "Malmö", NOW, null,
                "en", null, "Fully remote position", null, false, null, 1, false);
        var viaRemotePass = new NewJob("JOBTECH", "r2", null, null, "Java dev", "B", null, "Malmö", "Malmö", NOW, null,
                "sv", null, "text", null, false, null, 1, true);

        assertThat(JobPrefilter.apply(List.of(remote, viaRemotePass), profile(true, List.of(), "Stockholm"), NOW).kept()).hasSize(2);
        assertThat(JobPrefilter.apply(List.of(remote, viaRemotePass), profile(false, List.of(), "Stockholm"), NOW).kept()).isEmpty();
    }

    @Test
    void freeTextWebLocationsMatchByContainment() {
        var web = job("Web ad", "A", "Nacka kommun, Sverige", NOW, null);

        assertThat(JobPrefilter.apply(List.of(web), profile(false, List.of(), "Nacka"), NOW).kept()).hasSize(1);
    }
}
