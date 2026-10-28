package app.jobpilot.documents;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NewGraduatePhrasesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Du är nyexaminerad eller har någon års erfarenhet", "Vi välkomnar nyutexaminerade", "nyligen examinerad ingenjör",
            "Har nyligen tagit examen", "Du befinner dig i början av din karriär", "Jag är i början av min karriär",
            "Vi söker dig som är i början av er karriär", "Sökande bör vara i början av karriären",
            "We hire newly qualified engineers", "Our new graduate programme", "Recent graduates are welcome",
            "recently graduated from university", "someone recently finished their degree", "A fresh graduate with Java",
            "I am early in my career", "Early-career developers", "NYEXAMINERAD"})
    void findsSwedishAndEnglishPhrases(String text) {
        assertThat(NewGraduatePhrases.appearsIn(text)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Junior developer with two years of experience", "Vi söker en junior utvecklare", "Tre års erfarenhet av Java",
            "Vi söker både erfarna utvecklare och juniora", "graduated in 2024 with a master's degree",
            "Examen från GIH", "I am a graduate of Bangor University", "karriärväxling"})
    void leavesOrdinaryTextAlone(String text) {
        assertThat(NewGraduatePhrases.appearsIn(text)).as(text).isFalse();
    }

    @org.junit.jupiter.api.Test
    void returnsThePhraseAsWritten() {
        assertThat(NewGraduatePhrases.find("Du är i början av din karriär")).contains("i början av din karriär");
        assertThat(NewGraduatePhrases.find(null)).isEmpty();
    }
}
