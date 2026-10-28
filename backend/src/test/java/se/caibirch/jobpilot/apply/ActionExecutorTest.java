package se.caibirch.jobpilot.apply;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import se.caibirch.jobpilot.apply.ActionValidator.Validated;
import se.caibirch.jobpilot.apply.FormActions.MappedAction;
import se.caibirch.jobpilot.cvdb.CvModel.Profile;

class ActionExecutorTest extends PlaywrightTest {

    static final Profile PROFILE = new Profile(1L, "Cai Birch", "Systemutvecklare", "caibirch@proton.me", "076-185 95 27",
            "+46 76 185 95 27", "Stockholm", "https://www.caibirch.com", "https://github.com/CadfanCode", null, null, List.of());

    static MappedAction act(FormField f, String action, String value, String file, String source) {
        return new MappedAction(f.id(), action, value, file, "r", false, source);
    }

    Validated validated;

    ActionExecutor.Result run(List<FormField> fields, ApplyContext ctx, MappedAction... actions) {
        validated = ActionValidator.validate(fields, new FormActions(List.of(actions), List.of()), ctx);
        return new ActionExecutor(page, new BrowserGuard(page)).execute(validated.planned(), fields);
    }

    @Test
    void fillsAPontyFormUploadsTheCvAndLeavesConsentAndSubmitAlone() throws IOException {
        open("ponty.html");
        var fields = PageScripts.extract(page);
        Path cv = tempPdf("CV_Cai_Birch_Friday.pdf");
        var ctx = new ApplyContext(PROFILE, Map.of(), Map.of("cv", cv));

        var result = run(fields, ctx,
                act(byLabel(fields, "Namn"), "fill", "Cai Birch", "none", "profile"),
                act(byLabel(fields, "E-post"), "fill", "caibirch@proton.me", "none", "profile"),
                act(byLabel(fields, "Telefon"), "fill", "076-185 95 27", "none", "profile"),
                act(byLabel(fields, "Länk"), "fill", "https://www.caibirch.com", "none", "profile"),
                act(byLabel(fields, "Dokument"), "upload", "", "cv", "document"),
                act(byLabel(fields, "samtycker"), "check", "", "none", "cv"));

        assertThat(result.done()).extracting(ActionExecutor.Done::action).containsExactly("fill", "fill", "fill", "fill", "upload");
        assertThat(validated.skipped()).extracting(s -> s.reason()).containsExactly("left for you: consent, terms and privacy choices");
        assertThat(page.locator("#n").inputValue()).isEqualTo("Cai Birch");
        assertThat(page.locator("#e").inputValue()).isEqualTo("caibirch@proton.me");
        assertThat(page.locator("#c").isChecked()).isFalse();
        // the upload made the category select appear: it is reported as a new field for a second pass
        assertThat(result.newFields()).hasSize(1);
        assertThat(result.newFields().get(0).tag()).isEqualTo("select");
        assertThat(submitted()).isFalse();
        assertThat(clicks()).isEmpty();
    }

    @Test
    void aPersonuppgifterHeadingAndAFileFormatNoteDoNotBlockOrdinaryFields() throws IOException {
        open("personuppgifter.html");
        var fields = PageScripts.extract(page);
        Path cv = tempPdf("CV_Cai_Birch_Acme.pdf");
        var ctx = new ApplyContext(PROFILE, Map.of(), Map.of("cv", cv));

        var result = run(fields, ctx, act(byLabel(fields, "Namn"), "fill", "Cai Birch", "none", "profile"),
                act(byLabel(fields, "E-post"), "fill", "caibirch@proton.me", "none", "profile"),
                act(byLabel(fields, "Telefon"), "fill", "076-185 95 27", "none", "profile"),
                act(byLabel(fields, "Ladda upp CV"), "upload", "", "cv", "document"),
                act(byLabel(fields, "samtycker"), "check", "", "none", "cv"));

        assertThat(result.done()).extracting(ActionExecutor.Done::action).containsExactly("fill", "fill", "fill", "upload");
        assertThat(validated.skipped()).extracting(s -> s.reason()).containsExactly("left for you: consent, terms and privacy choices");
        assertThat(page.locator("#n").inputValue()).isEqualTo("Cai Birch");
        assertThat(page.locator("#gdpr").isChecked()).isFalse();
        assertThat(submitted()).isFalse();
    }

    @Test
    void aSecondPassFillsTheCategorySelectThatAppearedAfterTheUpload() throws IOException {
        open("ponty.html");
        var fields = PageScripts.extract(page);
        var ctx = new ApplyContext(PROFILE, Map.of(), Map.of("cv", tempPdf("CV_Cai_Birch_Friday.pdf")));
        var first = run(fields, ctx, act(byLabel(fields, "Dokument"), "upload", "", "cv", "document"));

        var second = run(first.newFields(), ctx, act(first.newFields().get(0), "select", "CV", "none", "document"));

        assertThat(second.done()).hasSize(1);
        assertThat(page.locator("#cats select").inputValue()).isEqualTo("CV");
        assertThat(submitted()).isFalse();
    }

    @Test
    void screeningRadiosFromMemoryAreChosenAndNothingElseIsGuessed() {
        open("screening.html");
        var fields = PageScripts.extract(page);
        String key = se.caibirch.jobpilot.cvdb.AnswerKeys.normalise("Tidigare anställd på Saab?");
        var ctx = new ApplyContext(PROFILE, Map.of(key, "Nej"), Map.of());
        FormField saabNej = fields.stream().filter(f -> f.type().equals("radio") && f.legend().contains("Saab") && f.optionLabel().equals("Nej")).findFirst().orElseThrow();
        FormField angularJa = fields.stream().filter(f -> f.type().equals("radio") && f.legend().contains("Angular") && f.optionLabel().equals("Ja")).findFirst().orElseThrow();

        var result = run(fields, ctx, act(saabNej, "check", "Nej", "none", "memory"), act(angularJa, "check", "Ja", "none", "memory"),
                act(byLabel(fields, "nyhetsbrev"), "check", "", "none", "cv"));

        assertThat(result.done()).extracting(ActionExecutor.Done::fieldId).containsExactly(saabNej.id());
        assertThat(page.locator("input[name=saab][value=nej]").isChecked()).isTrue();
        assertThat(page.locator("input[name=ng]:checked").count()).isZero();
        assertThat(page.locator("#news").isChecked()).isFalse();
        assertThat(validated.skipped()).extracting(s -> s.reason()).containsExactly("not an answer you gave before", "left for you: consent, terms and privacy choices");
        assertThat(submitted()).isFalse();
    }

    @Test
    void aPageThatRebuildsItsFieldsNeverGetsFalseSuccessReports() throws Exception {
        open("rerender.html");
        var fields = PageScripts.extract(page);

        var result = run(fields, new ApplyContext(PROFILE, Map.of(), Map.of()),
                act(byLabel(fields, "Förnamn"), "fill", "Cai", "none", "profile"),
                act(byLabel(fields, "Efternamn"), "fill", "Birch", "none", "profile"));

        assertThat(result.done()).as("the original fields no longer exist, so nothing may be reported as done").isEmpty();
        assertThat(result.skipped()).hasSize(2);
        assertThat(result.skipped()).extracting(s -> s.reason()).allMatch(r -> r.contains("changed") || r.contains("gone") || r.contains("did not keep"));
    }

    @Test
    void aValueTheFrameworkRejectsIsReportedAsNotKept() {
        open("ponty.html");
        // a controlled input that wipes whatever is typed
        page.evaluate("document.getElementById('n').addEventListener('input', e => { e.target.value = ''; })");
        var fields = PageScripts.extract(page);

        var result = run(fields, new ApplyContext(PROFILE, Map.of(), Map.of()), act(byLabel(fields, "Namn"), "fill", "Cai Birch", "none", "profile"));

        assertThat(result.done()).isEmpty();
        assertThat(result.skipped()).extracting(s -> s.reason()).containsExactly("the page did not keep the value");
    }

    @Test
    void fieldsInAnIframeAreFilledToo() {
        open("iframe_host.html");
        var fields = PageScripts.extract(page);

        var result = run(fields, new ApplyContext(PROFILE, Map.of(), Map.of()), act(byLabel(fields, "Namn"), "fill", "Cai Birch", "none", "profile"),
                act(byLabel(fields, "samtycker"), "check", "", "none", "cv"));

        assertThat(result.done()).hasSize(1);
        assertThat(page.frames().get(1).locator("#n").inputValue()).isEqualTo("Cai Birch");
        assertThat(submitted()).isFalse();
    }
}
