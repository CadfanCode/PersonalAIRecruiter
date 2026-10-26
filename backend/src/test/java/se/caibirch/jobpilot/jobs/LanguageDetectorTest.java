package se.caibirch.jobpilot.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LanguageDetectorTest {

    @Test
    void detectsSwedishAndEnglish() {
        assertThat(LanguageDetector.detect("Vi söker en utvecklare som har erfarenhet av Java och Spring.")).isEqualTo("sv");
        assertThat(LanguageDetector.detect("We are looking for a developer with experience in Java and Spring.")).isEqualTo("en");
    }

    @Test
    void emptyTextDefaultsToSwedish() {
        assertThat(LanguageDetector.detect(null)).isEqualTo("sv");
        assertThat(LanguageDetector.detect(" ")).isEqualTo("sv");
    }
}
