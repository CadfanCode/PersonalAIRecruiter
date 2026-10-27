package se.caibirch.jobpilot.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import se.caibirch.jobpilot.documents.DocumentModel.Severity;
import se.caibirch.jobpilot.documents.DocumentModel.Violation;

class DocumentStyleValidatorTest {

    static final List<String> BANNED_SV = List.of("brinner för", "passionerad", "inte bara ... utan också", "jag är övertygad om att");
    static final List<String> BANNED_EN = List.of("passionate about", "leverage", "not only ... but also", "I believe I would be a great fit");

    static Map<String, String> doc(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    List<Violation> check(boolean asksNewGrad, List<String> banned, boolean letter, String... pairs) {
        return new DocumentStyleValidator(asksNewGrad, banned, Set.of("Knowit")).validate(doc(pairs), letter);
    }

    List<String> codes(List<Violation> v) {
        return v.stream().map(Violation::code).toList();
    }

    @Test
    void emAndEnDashesFailEverywhere() {
        var v = check(false, BANNED_SV, false, "headline", "Javautvecklare – backend", "gapNote", "Övrig anställning 2019—2021",
                "sections[0].items[0].dateLabel", "2018–2021", "profileParagraphs[0]", "Rent hyphen-ok och 2018-2021");

        assertThat(v).extracting(Violation::where).containsExactly("headline", "gapNote", "sections[0].items[0].dateLabel");
        assertThat(v).allMatch(x -> x.severity() == Severity.FAIL && x.code().equals("dash"));
        assertThat(v.get(0).text()).contains("U+2013");
    }

    @Test
    void plainHyphensAreFine() {
        assertThat(check(false, BANNED_SV, false, "profileParagraphs[0]", "Full-stack, 2018-2021, Java-utvecklare")).isEmpty();
    }

    @Test
    void newGraduateWordingFailsUnlessTheAdAsksForIt() {
        String text = "Som nyexaminerad systemutvecklare söker jag...";
        assertThat(codes(check(false, BANNED_SV, true, "paragraphs[0]", text))).containsExactly("new_graduate");
        assertThat(check(true, BANNED_SV, true, "paragraphs[0]", text)).isEmpty();
        assertThat(codes(check(false, BANNED_EN, true, "paragraphs[0]", "I am a recent graduate"))).containsExactly("new_graduate");
    }

    @Test
    void lettersMustNotOpenWithMyNameIs() {
        assertThat(codes(check(false, BANNED_SV, true, "subject", "Ansökan", "paragraphs[0]", "Jag heter Cai Birch och söker tjänsten")))
                .containsExactly("bad_opening");
        assertThat(codes(check(false, BANNED_EN, true, "paragraphs[0]", "My name is Cai and I apply"))).containsExactly("bad_opening");
        assertThat(check(false, BANNED_SV, true, "paragraphs[0]", "Jag är systemutvecklare", "paragraphs[1]", "Jag heter inte så.")).isEmpty();
        assertThat(check(false, BANNED_SV, false, "profileParagraphs[0]", "Jag heter Cai")).as("only letters").isEmpty();
    }

    @Test
    void bannedPhrasesWarnCaseInsensitivelyAndAcrossTwoParts() {
        var v = check(false, BANNED_SV, true, "paragraphs[0]", "Jag Brinner För backend", "paragraphs[1]",
                "Det är inte bara tekniken, utan också teamet som lockar", "paragraphs[2]", "Jag är övertygad om att det passar");

        assertThat(v).hasSize(3).allMatch(x -> x.severity() == Severity.WARN && x.code().equals("banned_phrase"));
        assertThat(codes(check(false, BANNED_EN, true, "paragraphs[0]", "Not only a developer but also a tester"))).containsExactly("banned_phrase");
        assertThat(check(false, BANNED_EN, true, "paragraphs[0]", "I do not only write code. It also matters. But also here")).as("a full stop separates the parts").isEmpty();
    }

    @Test
    void anotherEmployersNameFromAnExampleLetterFails() {
        var v = check(false, BANNED_SV, true, "paragraphs[1]", "Jag gillar kulturen på Knowit mycket");

        assertThat(codes(v)).containsExactly("foreign_employer");
        assertThat(v.get(0).severity()).isEqualTo(Severity.FAIL);
    }
}
