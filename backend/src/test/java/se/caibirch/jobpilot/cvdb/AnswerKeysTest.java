package se.caibirch.jobpilot.cvdb;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnswerKeysTest {

    @Test
    void lowercasesStripsPunctuationAndCollapsesWhitespace() {
        assertThat(AnswerKeys.normalise("  Rätt att arbeta i Sverige?  ")).isEqualTo("rätt att arbeta i sverige");
        assertThat(AnswerKeys.normalise("Erfarenhet av Angular,\n TypeScript  och React (från arbete)!"))
                .isEqualTo("erfarenhet av angular typescript och react från arbete");
    }

    @Test
    void sameQuestionWithDifferentFormattingGivesTheSameKey() {
        assertThat(AnswerKeys.normalise("Do you have experience with Figma?"))
                .isEqualTo(AnswerKeys.normalise("do you have experience with figma"));
    }
}
