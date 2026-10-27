package se.caibirch.jobpilot.apply;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import se.caibirch.jobpilot.apply.ActionValidator.Validated;
import se.caibirch.jobpilot.apply.FormActions.MappedAction;
import se.caibirch.jobpilot.apply.FormActions.Question;
import se.caibirch.jobpilot.cvdb.AnswerKeys;
import se.caibirch.jobpilot.cvdb.CvModel.Language;
import se.caibirch.jobpilot.cvdb.CvModel.Profile;

class ActionValidatorTest {

    static final Profile PROFILE = new Profile(1L, "Cai Birch", "Systemutvecklare", "caibirch@proton.me", "076-185 95 27",
            "+46 76 185 95 27", "Stockholm", "https://www.caibirch.com", "https://github.com/CadfanCode", null, null, null,
            List.of(new Language("Swedish", "fluent")));

    static FormField text(String id, String label) {
        return field(id, "input", "text", label, "", false);
    }

    static FormField field(String id, String tag, String type, String label, String value, boolean checked) {
        return new FormField(id, 0, tag, type, "", label, "", "", "", "", "", "", "", false, false, true, value, checked, false, "",
                false, List.of());
    }

    static FormField radio(String id, String legend, String option) {
        return new FormField(id, 0, "input", "radio", "q", "", "", "", "", legend, "", option, "q", false, false, true, option, false,
                false, "", false, List.of());
    }

    static MappedAction act(String id, String action, String value, String source) {
        return new MappedAction(id, action, value, "none", "r", false, source);
    }

    ApplyContext ctx(Map<String, String> memory, Map<String, Path> files) {
        return new ApplyContext(PROFILE, memory, files);
    }

    Validated run(List<FormField> fields, ApplyContext ctx, MappedAction... actions) {
        return ActionValidator.validate(fields, new FormActions(List.of(actions), List.of()), ctx);
    }

    List<String> reasons(Validated v) {
        return v.skipped().stream().map(ActionValidator.Skipped::reason).toList();
    }

    @Test
    void acceptsProfileValuesForMatchingFields() {
        var fields = List.of(text("jp-1", "Förnamn"), text("jp-2", "Efternamn"), field("jp-3", "input", "email", "E-post", "", false),
                field("jp-4", "input", "tel", "Telefon", "", false), text("jp-5", "Ort"), text("jp-6", "Namn"));

        var v = run(fields, ctx(Map.of(), Map.of()), act("jp-1", "fill", "Cai", "profile"), act("jp-2", "fill", "Birch", "profile"),
                act("jp-3", "fill", "caibirch@proton.me", "profile"), act("jp-4", "fill", "+46 76 185 95 27", "profile"),
                act("jp-5", "fill", "Stockholm", "profile"), act("jp-6", "fill", "Cai Birch", "profile"));

        assertThat(v.planned()).hasSize(6);
        assertThat(v.skipped()).isEmpty();
    }

    @Test
    void refusesValuesThatDifferFromTheProfileForNameEmailPhoneAndCity() {
        var fields = List.of(text("jp-1", "Förnamn"), field("jp-3", "input", "email", "E-post", "", false),
                field("jp-4", "input", "tel", "Telefon", "", false), text("jp-5", "Ort"));

        var v = run(fields, ctx(Map.of(), Map.of()), act("jp-1", "fill", "Carl", "profile"), act("jp-3", "fill", "other@example.com", "profile"),
                act("jp-4", "fill", "070-000 00 00", "profile"), act("jp-5", "fill", "Göteborg", "cv"));

        assertThat(v.planned()).isEmpty();
        assertThat(reasons(v)).containsOnly("does not match your profile for this field");
    }

    @Test
    void neverTouchesPasswordHiddenOrCredentialFields() {
        var fields = List.of(field("jp-1", "input", "password", "Lösenord", "", false), field("jp-2", "input", "hidden", "x", "", false),
                text("jp-3", "BankID personnummer"), text("jp-4", "Engångskod"));

        var v = run(fields, ctx(Map.of(), Map.of()), act("jp-1", "fill", "hunter2", "profile"), act("jp-2", "fill", "x", "profile"),
                act("jp-3", "fill", "19900101-1234", "profile"), act("jp-4", "fill", "123456", "profile"));

        assertThat(v.planned()).isEmpty();
        assertThat(v.skipped()).hasSize(4).allMatch(s -> s.reason().startsWith("refused"));
    }

    @Test
    void leavesConsentTermsAndTalentPoolChoicesToTheUser() {
        var fields = List.of(field("jp-1", "input", "checkbox", "Jag samtycker till behandling av personuppgifter (GDPR)", "", false),
                field("jp-2", "input", "checkbox", "Gå med i vår talent community", "", false),
                field("jp-3", "input", "checkbox", "Jag godkänner villkoren", "", true), radio("jp-4", "Terms", "I accept the terms"));

        var v = run(fields, ctx(Map.of(), Map.of()), act("jp-1", "check", "", "profile"), act("jp-2", "check", "", "cv"),
                act("jp-3", "uncheck", "", "profile"), act("jp-4", "check", "", "cv"));

        assertThat(v.planned()).isEmpty();
        assertThat(v.skipped()).hasSize(4).allMatch(s -> s.reason().startsWith("left for you"));
    }

    @Test
    void unknownFieldIdsAreSkippedAndTheActionMustFitTheFieldType() {
        var fields = List.of(field("jp-1", "input", "checkbox", "Körkort", "", false), text("jp-2", "Meddelande"),
                field("jp-3", "select", "select", "Land", "", false), field("jp-4", "input", "file", "Ladda upp CV", "", false));

        var v = run(fields, ctx(Map.of(), Map.of()), act("jp-99", "fill", "x", "cv"), act("jp-1", "fill", "yes", "cv"),
                act("jp-2", "check", "", "cv"), act("jp-3", "fill", "Sverige", "cv"), act("jp-4", "fill", "x", "cv"));

        assertThat(v.planned()).isEmpty();
        assertThat(reasons(v)).containsExactly("not a field on this page", "fill needs a text field, this is checkbox",
                "check needs a checkbox or radio, this is text", "fill needs a text field, this is select", "fill needs a text field, this is file");
    }

    @Test
    void selectNeedsAnExistingOption() {
        FormField select = new FormField("jp-3", 0, "select", "select", "", "Land", "", "", "", "", "", "", "", false, false, true, "",
                false, false, "", false, List.of(new FormField.Option("SE", "Sverige"), new FormField.Option("NO", "Norge")));

        var ok = run(List.of(select), ctx(Map.of(), Map.of()), act("jp-3", "select", "sverige", "cv"));
        var bad = run(List.of(select), ctx(Map.of(), Map.of()), act("jp-3", "select", "Atlantis", "cv"));

        assertThat(ok.planned()).hasSize(1);
        assertThat(reasons(bad)).containsExactly("no such option: Atlantis");
    }

    @Test
    void uploadsUseOnlyApprovedDocuments() {
        var fields = List.of(field("jp-4", "input", "file", "Ladda upp - CV", "", false), field("jp-5", "input", "file", "Övriga filer", "", false));
        Path cv = Path.of("/tmp/CV_Cai_Birch_X.pdf");

        var v = run(fields, ctx(Map.of(), Map.of("cv", cv)), new MappedAction("jp-4", "upload", "", "cv", "r", false, "document"),
                new MappedAction("jp-5", "upload", "", "letter", "r", false, "document"));

        assertThat(v.planned()).hasSize(1);
        assertThat(v.planned().get(0).file()).isEqualTo(cv);
        assertThat(reasons(v)).containsExactly("your cover letter is not approved yet, so it is not uploaded");
        assertThat(run(fields, ctx(Map.of(), Map.of()), new MappedAction("jp-4", "upload", "", "../../etc/passwd", "r", false, "document")).skipped())
                .extracting(ActionValidator.Skipped::reason).containsExactly("upload needs file cv or letter");
    }

    @Test
    void screeningAnswersFromMemoryMustMatchTheStoredAnswer() {
        FormField q = text("jp-7", "Erfarenhet av Figma?");
        String key = AnswerKeys.normalise("Erfarenhet av Figma?");
        var memory = Map.of(key, "Nej, men mycket angelägen att lära mig");

        var ok = run(List.of(q), ctx(memory, Map.of()), act("jp-7", "fill", "Nej, men mycket angelägen att lära mig", "memory"));
        var invented = run(List.of(q), ctx(memory, Map.of()), act("jp-7", "fill", "Ja, flera år", "memory"));
        var unknown = run(List.of(text("jp-8", "Har du körkort?")), ctx(memory, Map.of()), act("jp-8", "fill", "Ja", "memory"));

        assertThat(ok.planned()).hasSize(1);
        assertThat(reasons(invented)).containsExactly("not an answer you gave before");
        assertThat(reasons(unknown)).containsExactly("not an answer you gave before");
    }

    @Test
    void radioAnswersFromMemoryMustMatchTheOptionTheUserGave() {
        var ja = radio("jp-1", "Tidigare anställd på Saab", "Ja");
        var nej = radio("jp-2", "Tidigare anställd på Saab", "Nej");
        var memory = Map.of(AnswerKeys.normalise("Tidigare anställd på Saab"), "Nej");

        var v = run(List.of(ja, nej), ctx(memory, Map.of()), act("jp-1", "check", "Ja", "memory"), act("jp-2", "check", "Nej", "memory"));

        assertThat(v.planned()).extracting(p -> p.field().id()).containsExactly("jp-2");
        assertThat(reasons(v)).containsExactly("not an answer you gave before");
    }

    @Test
    void anActionWithoutASourceIsNotRun() {
        var v = run(List.of(text("jp-1", "Meddelande")), ctx(Map.of(), Map.of()), act("jp-1", "fill", "Hej", null),
                act("jp-1", "fill", "Hej", "magic"));

        assertThat(v.planned()).isEmpty();
        assertThat(reasons(v)).contains("no source given for the value");
    }

    @Test
    void fieldsThatAlreadyHoldADifferentValueAreLeftAlone() {
        FormField prefilled = field("jp-1", "input", "text", "Meddelande", "Mitt eget utkast", false);

        var v = run(List.of(prefilled), ctx(Map.of(), Map.of()), act("jp-1", "fill", "Hej!", "cv"));

        assertThat(reasons(v)).containsExactly("already has a value; left as it is");
    }

    @Test
    void needsUserAndSkipBecomeQuestionsNotActions() {
        var fields = List.of(text("jp-1", "Har du erfarenhet av Angular?"), text("jp-2", "Ort"));
        var mapped = new FormActions(List.of(new MappedAction("jp-1", "skip", "", "none", "needs judgement", true, "none"),
                new MappedAction("jp-2", "skip", "", "none", "unclear", false, "none")), List.of(new Question("jp-1", "Experience with Angular?")));

        var v = ActionValidator.validate(fields, mapped, ctx(Map.of(), Map.of()));

        assertThat(v.planned()).isEmpty();
        assertThat(v.questions()).extracting(Question::fieldId).containsExactly("jp-1");
        assertThat(v.questions().get(0).question()).isEqualTo("Har du erfarenhet av Angular?");
    }

    @Test
    void questionsForConsentOrPasswordFieldsAreDropped() {
        var fields = List.of(field("jp-1", "input", "password", "Lösenord", "", false), text("jp-2", "Samtycke"));
        var mapped = new FormActions(List.of(), List.of(new Question("jp-1", "Password?"), new Question("jp-2", "Consent?"), new Question("jp-404", "?")));

        assertThat(ActionValidator.validate(fields, mapped, ctx(Map.of(), Map.of())).questions()).isEmpty();
    }

    @Test
    void checkOnAnAlreadyTickedBoxOrAnUntickOfARadioIsNotRun() {
        var fields = List.of(field("jp-1", "input", "checkbox", "Körkort B", "", true), radio("jp-2", "Q", "Ja"));

        var v = run(fields, ctx(Map.of(), Map.of()), act("jp-1", "check", "", "cv"), act("jp-2", "uncheck", "", "cv"));

        assertThat(reasons(v)).containsExactly("already ticked", "a radio button cannot be unticked");
    }

    @Test
    void valuesAreCleanedAndCapped() {
        var long5001 = "x".repeat(ActionValidator.MAX_VALUE + 1);

        var v = run(List.of(text("jp-1", "Meddelande"), text("jp-2", "Brev")), ctx(Map.of(), Map.of()),
                act("jp-1", "fill", "Hej\u0000 på dig\u0007", "cv"), act("jp-2", "fill", long5001, "cv"));

        assertThat(v.planned().get(0).value()).isEqualTo("Hej på dig");
        assertThat(reasons(v)).containsExactly("the value is too long");
    }

    @Test
    void theSameFieldProposedTwiceIsUsedOnce() {
        var v = run(List.of(text("jp-1", "Meddelande")), ctx(Map.of(), Map.of()), act("jp-1", "fill", "A", "cv"), act("jp-1", "fill", "B", "cv"));

        assertThat(v.planned()).hasSize(1);
        assertThat(reasons(v)).containsExactly("proposed twice; only the first is used");
    }
}
