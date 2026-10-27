package se.caibirch.jobpilot.apply;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class FormExtractorTest extends PlaywrightTest {

    List<FormField> extract(String fixture) {
        open(fixture);
        return PageScripts.extract(page);
    }

    @Test
    void readsLabelsTypesAndTheMultipleFileInputOfAPontyForm() {
        var fields = extract("ponty.html");

        assertThat(fields).extracting(FormField::type).containsExactly("text", "email", "tel", "url", "file", "checkbox");
        assertThat(byLabel(fields, "Namn").label()).isEqualTo("Namn");
        assertThat(byLabel(fields, "Telefon").type()).isEqualTo("tel");
        FormField files = byLabel(fields, "Dokument");
        assertThat(files.multiple()).isTrue();
        assertThat(byLabel(fields, "samtycker").label()).contains("personuppgifter");
        assertThat(fields).allMatch(f -> f.id().matches("jp-\\d+"));
    }

    @Test
    void buttonsAndSubmitControlsAreNotFields() {
        assertThat(extract("ponty.html")).noneMatch(f -> f.type().equals("submit") || f.type().equals("button"));
        assertThat(extract("workday.html")).extracting(FormField::allText).noneMatch(t -> t.contains("Save and Continue"));
    }

    @Test
    void passwordFieldsAreReportedSoTheyCanBeRefused() {
        var fields = extract("varbi_login.html");

        assertThat(fields).extracting(FormField::type).contains("password");
        assertThat(byLabel(fields, "Lösenord").type()).isEqualTo("password");
    }

    @Test
    void radioGroupsCarryTheirQuestionAndOptionText() {
        var fields = extract("varbi_form.html");

        var radios = fields.stream().filter(f -> f.type().equals("radio")).toList();
        assertThat(radios).hasSize(4);
        assertThat(radios.get(0).legend()).isEqualTo("Har du rätt att arbeta i Sverige?");
        assertThat(radios.get(0).optionLabel()).isEqualTo("Ja");
        assertThat(radios.get(1).optionLabel()).isEqualTo("Nej");
        assertThat(radios.get(0).groupName()).isEqualTo("work");
        assertThat(radios.get(0).questionText()).isEqualTo("Har du rätt att arbeta i Sverige?");
    }

    @Test
    void fileInputsHiddenBehindAStyledButtonAreStillFound() {
        page.setContent("<label for=f>Ladda upp CV</label><input id=f type=file style='display:none'><input id=t style='display:none'>");

        var fields = PageScripts.extract(page);

        assertThat(fields).extracting(FormField::type).containsExactly("file");
        assertThat(fields.get(0).visible()).isFalse();
    }

    @Test
    void optionsOfADropDownAndTheAutocompleteFlagAreRead() {
        var fields = extract("workday.html");

        FormField country = byLabel(fields, "Country");
        assertThat(country.options()).extracting(FormField.Option::label).containsExactly("Select", "Sweden", "Norway");
        assertThat(byLabel(fields, "Skills").autocomplete()).isTrue();
        assertThat(byLabel(fields, "First Name").legend()).isEqualTo("Legal Name");
    }

    @Test
    void fieldsInsideAnIframeGetAFramePrefix() {
        var fields = extract("iframe_host.html");

        assertThat(fields).isNotEmpty().allMatch(f -> f.id().startsWith("f1-") && f.frame() == 1);
    }

    @Test
    void idsAreStableAcrossExtractions() {
        open("ponty.html");
        var first = PageScripts.extract(page);
        var second = PageScripts.extract(page);

        assertThat(second).extracting(FormField::id).containsExactlyElementsOf(first.stream().map(FormField::id).toList());
    }

    @Test
    void invisibleFieldsInAClosedModalAreNotExtracted() {
        assertThat(extract("jobshark_closed.html")).extracting(FormField::type).containsExactly("file");
        assertThat(PageScripts.detect(page).fieldCount()).isEqualTo(1);
    }

    @Test
    void thePageStateTellsLoginFromFormFromAdFromError() {
        open("varbi_login.html");
        assertThat(PageClassifier.classify(PageScripts.detect(page))).isEqualTo(PageClassifier.Kind.WAITING_FOR_SIGN_IN);
        open("varbi_form.html");
        assertThat(PageClassifier.classify(PageScripts.detect(page))).isEqualTo(PageClassifier.Kind.FORM);
        open("platsbanken_ad.html");
        assertThat(PageClassifier.classify(PageScripts.detect(page))).isEqualTo(PageClassifier.Kind.MANUAL_APPLY);
        open("workday_error.html");
        assertThat(PageClassifier.classify(PageScripts.detect(page))).isEqualTo(PageClassifier.Kind.SESSION_ERROR);
        open("empty.html");
        assertThat(PageClassifier.classify(PageScripts.detect(page))).isEqualTo(PageClassifier.Kind.EMPTY);
        open("ponty.html");
        assertThat(PageClassifier.classify(PageScripts.detect(page))).isEqualTo(PageClassifier.Kind.FORM);
        open("iframe_host.html");
        assertThat(PageClassifier.classify(PageScripts.detect(page))).as("a form inside an iframe still counts").isEqualTo(PageClassifier.Kind.FORM);
    }
}
