package app.jobpilot.cvdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import app.jobpilot.cvdb.CvModel.Entry;
import app.jobpilot.cvdb.CvModel.Kind;

/** Pairs taken from a real importCv run on the Knowit CV against the real seed (26 Oct 2026). */
class EntryMatcherTest {

    static Entry entry(Kind kind, String title, String titleEn, String org) {
        return new Entry(1L, kind, title, titleEn, org, null, null, null, false, null, null, 0, false, List.of(), List.of());
    }

    List<Entry> seed = List.of(
            entry(Kind.OWN_BUSINESS, "Grundare och utvecklare, Skepparklar", "Founder and developer, Skepparklar", "Skepparklar (www.skepparklar.se)"),
            entry(Kind.INTERNSHIP, "Systemutvecklare (praktik)", "Software Developer (intern)", "Braive AB"),
            entry(Kind.INTERNSHIP, "Systemutvecklare (praktik)", "Software Developer (intern)", "Omika Health AB"),
            entry(Kind.EDUCATION, "Masterexamen i idrottsvetenskap", "MSc in Sport Science", "Gymnastik- och idrottshögskolan (GIH)"),
            entry(Kind.EDUCATION, "Kandidatexamen i idrottsvetenskap", "BSc in Sport Science", "Bangor University"),
            entry(Kind.CERTIFICATION, "Associate Fellow of Advance HE", null, "Advance HE (via Loughborough University)"),
            entry(Kind.CONFERENCE, "Jfokus 2026", null, null),
            entry(Kind.AWARD, "3rd place, Innovation Pioneers Hackathon 2025", null, null),
            entry(Kind.PROJECT, "Portfolio with the AI parrot Polly", null, "www.caibirch.com"),
            entry(Kind.OTHER, "Personal", null, null));

    boolean matches(Kind kind, String title, String org) {
        return EntryMatcher.findMatch(new EntryMatcher.Key(kind, title, null, org, null), seed).isPresent();
    }

    @Test
    void organisationAbbreviationsAndSuffixesMatch() {
        assertThat(matches(Kind.EDUCATION, "Masterexamen i idrottsvetenskap", "GIH")).isTrue();
        assertThat(matches(Kind.CERTIFICATION, "Associate Fellow of Advance HE", "Advance HE")).isTrue();
        assertThat(matches(Kind.OWN_BUSINESS, "Skepparklar", "Skepparklar")).isTrue();
    }

    @Test
    void swedishAndEnglishTitlesOfTheSameEntryMatchWhenThereIsNoOrganisation() {
        assertThat(matches(Kind.AWARD, "3:e plats, Innovation Pioneers Hackathon 2025", "Innovation Pioneers Hackathon")).isTrue();
        assertThat(matches(Kind.PROJECT, "Portfolio med AI-papegojan Polly", null)).isTrue();
        assertThat(matches(Kind.CONFERENCE, "Jfokus 2026", "Jfokus")).isTrue();
    }

    @Test
    void sameTitleAtADifferentEmployerIsAnotherEntry() {
        Entry hit = EntryMatcher.findMatch(new EntryMatcher.Key(Kind.INTERNSHIP, "Systemutvecklare (praktik)", null, "Omika Health AB", null), seed).orElseThrow();

        assertThat(hit.organisation()).isEqualTo("Omika Health AB");
        assertThat(matches(Kind.INTERNSHIP, "Systemutvecklare (praktik)", "Ericsson AB")).isFalse();
    }

    @Test
    void newMaterialIsNotFlagged() {
        assertThat(matches(Kind.OTHER, "Profil", null)).isFalse();
        assertThat(matches(Kind.LANGUAGE, "Svenska", null)).isFalse();
        assertThat(matches(Kind.WORK, "Idrottslärare", "Nacka skola")).isFalse();
        assertThat(matches(Kind.EDUCATION, "Masterexamen i idrottsvetenskap", "Lunds universitet")).isFalse();
    }

    @Test
    void kindMustMatch() {
        assertThat(matches(Kind.WORK, "Jfokus 2026", null)).isFalse();
    }

    private static Entry job(String title, String org, String start) {
        return new Entry(2L, Kind.WORK, title, null, org, null, start, null, false, null, null, 0, false, List.of(), List.of());
    }

    @Test
    void aPromotionAtTheSameEmployerIsADifferentEntry() {
        List<Entry> stored = List.of(job("Undersköterska", "Akademiska sjukhuset", "2015-01"));

        assertThat(EntryMatcher.findMatch(new EntryMatcher.Key(Kind.WORK, "Sjuksköterska", null, "Akademiska sjukhuset", "2019-01"), stored)).isEmpty();
    }

    @Test
    void theSameRoleWordedDifferentlyOrStartingTheSameMonthIsTheSameEntry() {
        List<Entry> stored = List.of(job("Sjuksköterska", "Capio", "2020"));

        assertThat(EntryMatcher.findMatch(new EntryMatcher.Key(Kind.WORK, "Legitimerad sjuksköterska", null, "Capio AB", "2020-03"), stored)).isPresent();
        assertThat(EntryMatcher.findMatch(new EntryMatcher.Key(Kind.WORK, "Avdelningsansvarig", null, "Capio", "2020-09"), stored))
                .as("a different title but the same start period at the same employer").isPresent();
        assertThat(EntryMatcher.sameStart(null, "2020")).isFalse();
        assertThat(EntryMatcher.sameStart("2020-03", "2020")).isTrue();
        assertThat(EntryMatcher.sameStart("2019", "2020")).isFalse();
    }

    @Test
    void factsAreTheSameWhenTheirWordsNearlyAre() {
        assertThat(FactMatcher.similar("Triage on the emergency ward.", "triage on the emergency ward")).isTrue();
        assertThat(FactMatcher.similar("Built microservices in Java.", "Led a team of five engineers")).isFalse();
        assertThat(FactMatcher.similar(null, "x")).isFalse();
    }
}
