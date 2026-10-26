package se.caibirch.jobpilot.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DedupeTest {

    @Test
    void keyIsLowercaseEmployerNormalisedTitleAndMunicipality() {
        assertThat(Dedupe.key("Comstream AB", "Junior Java-utvecklare", "Stockholm"))
                .isEqualTo("comstream ab|junior java utvecklare|stockholm");
    }

    @Test
    void genderSuffixesPunctuationAndSpacingDoNotMatter() {
        assertThat(Dedupe.key("Saab AB", "Software Developer (m/k/x) - 9Air!", "Järfälla"))
                .isEqualTo(Dedupe.key("  SAAB AB ", "software   developer  9air", "järfälla"));
        assertThat(Dedupe.normaliseTitle("Utvecklare (f/m/d)")).isEqualTo("utvecklare");
    }

    @Test
    void differentMunicipalityOrTitleIsADifferentAd() {
        assertThat(Dedupe.key("Saab", "Developer", "Järfälla")).isNotEqualTo(Dedupe.key("Saab", "Developer", "Linköping"));
        assertThat(Dedupe.key("Saab", "Developer", "Järfälla")).isNotEqualTo(Dedupe.key("Saab", "Senior developer", "Järfälla"));
    }

    @Test
    void missingPartsDoNotThrow() {
        assertThat(Dedupe.key(null, null, null)).isEqualTo("||");
    }
}
