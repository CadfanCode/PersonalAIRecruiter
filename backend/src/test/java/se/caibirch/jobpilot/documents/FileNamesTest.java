package se.caibirch.jobpilot.documents;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import se.caibirch.jobpilot.documents.DocumentModel.Kind;

class FileNamesTest {

    @Test
    void followsTheSpecPatterns() {
        assertThat(FileNames.of(Kind.CV, "sv", "Cai Birch", "Comstream AB")).isEqualTo("CV_Cai_Birch_Comstream.pdf");
        assertThat(FileNames.of(Kind.LETTER, "sv", "Cai Birch", "Comstream AB")).isEqualTo("Personligt_brev_Cai_Birch_Comstream.pdf");
        assertThat(FileNames.of(Kind.LETTER, "en", "Cai Birch", "Harbourline Logistics AB")).isEqualTo("Cover_letter_Cai_Birch_Harbourline_Logistics.pdf");
    }

    @Test
    void isAsciiSafeForAnyEmployer() {
        assertThat(FileNames.of(Kind.CV, "sv", "Cai Birch", "Åkerbär & Söner AB")).isEqualTo("CV_Cai_Birch_Akerbar_Soner.pdf");
        assertThat(FileNames.of(Kind.CV, "sv", "Cai Birch", "../../etc/passwd")).isEqualTo("CV_Cai_Birch_etc_passwd.pdf");
        assertThat(FileNames.of(Kind.CV, "sv", "Cai Birch", "Ørsted A/S")).isEqualTo("CV_Cai_Birch_Orsted.pdf");
        assertThat(FileNames.of(Kind.CV, "sv", "Cai Birch", "会社")).isEqualTo("CV_Cai_Birch_Unknown.pdf");
        assertThat(FileNames.of(Kind.CV, "sv", "Cai Birch", null)).isEqualTo("CV_Cai_Birch_Unknown.pdf");
        assertThat(FileNames.of(Kind.CV, "sv", "Cai Birch", "Quest Consulting Sverige AB")).isEqualTo("CV_Cai_Birch_Quest_Consulting.pdf");
    }

    @Test
    void longEmployersAreShortened() {
        String name = FileNames.of(Kind.CV, "sv", "Cai Birch", "A".repeat(200));
        assertThat(name.length()).isLessThan(70);
        assertThat(name).matches("[A-Za-z0-9_]+\\.pdf");
    }
}
