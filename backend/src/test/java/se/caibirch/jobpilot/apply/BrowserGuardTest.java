package se.caibirch.jobpilot.apply;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import com.microsoft.playwright.Locator;

import org.junit.jupiter.api.Test;

import se.caibirch.jobpilot.apply.BrowserGuard.ClickPurpose;
import se.caibirch.jobpilot.apply.BrowserGuard.Outcome;

class BrowserGuardTest extends PlaywrightTest {

    List<FormField> fields(String fixture) {
        open(fixture);
        return PageScripts.extract(page);
    }

    BrowserGuard guard() {
        return new BrowserGuard(page);
    }

    @Test
    void fillsAnOrdinaryTextFieldAndReadsTheValueBack() {
        var f = fields("ponty.html");

        Outcome o = guard().fill(byLabel(f, "Namn"), "Cai Birch");

        assertThat(o.done()).isTrue();
        assertThat(guard().currentValue(byLabel(f, "Namn"))).isEqualTo("Cai Birch");
        assertThat(submitted()).isFalse();
    }

    @Test
    void neverTouchesAPasswordField() {
        var f = fields("varbi_login.html");

        Outcome o = guard().fill(byLabel(f, "Lösenord"), "hunter2");

        assertThat(o.done()).isFalse();
        assertThat(o.reason()).startsWith("refused: password");
        assertThat(page.locator("#pw").inputValue()).isEmpty();
    }

    @Test
    void refusesToTickOrUntickConsentBoxesAnywhere() {
        var f = fields("varbi_form.html");
        FormField gdpr = byLabel(f, "GDPR");

        assertThat(guard().check(gdpr, true).done()).isFalse();
        assertThat(guard().check(gdpr, true).reason()).contains("consent");
        assertThat(page.locator("#gdpr").isChecked()).isFalse();

        var ponty = fields("ponty.html");
        assertThat(guard().check(byLabel(ponty, "samtycker"), true).done()).isFalse();
        assertThat(page.locator("#c").isChecked()).isFalse();

        var workday = fields("workday.html");
        assertThat(guard().check(byLabel(workday, "talent community"), true).done()).isFalse();
        assertThat(page.locator("#tc").isChecked()).isFalse();
    }

    @Test
    void picksARadioButNeverUnticksOne() {
        var f = fields("varbi_form.html");
        FormField nej = f.stream().filter(x -> x.type().equals("radio") && x.legend().startsWith("Har du rätt") && x.optionLabel().equals("Nej")).findFirst().orElseThrow();

        assertThat(guard().check(nej, true).done()).isTrue();
        assertThat(page.locator("input[name=work][value=nej]").isChecked()).isTrue();
        assertThat(guard().check(nej, false).done()).isFalse();
    }

    @Test
    void selectsAnOptionByLabelOrValue() {
        var f = fields("workday.html");

        assertThat(guard().select(byLabel(f, "Country"), "Sweden").done()).isTrue();
        assertThat(page.locator("#co").inputValue()).isEqualTo("SE");
        assertThat(guard().select(byLabel(f, "Country"), "NO").done()).isTrue();
        assertThat(guard().select(byLabel(f, "Country"), "Atlantis").done()).isFalse();
    }

    @Test
    void uploadsAFileAndNeverAnythingOutsideAFileInput() throws IOException {
        var f = fields("ponty.html");

        assertThat(guard().upload(byLabel(f, "Dokument"), tempPdf("CV_Cai_Birch_Friday.pdf")).done()).isTrue();
        assertThat(page.locator("#cats select").count()).isEqualTo(1);
        assertThat(guard().upload(byLabel(f, "Namn"), tempPdf("x.pdf")).done()).isFalse();
        assertThat(guard().upload(byLabel(f, "Dokument"), java.nio.file.Path.of("/nonexistent/file.pdf")).reason()).contains("does not exist");
    }

    @Test
    void picksAnAutocompleteSuggestionOnlyWhenItMatchesExactly() {
        var f = fields("workday.html");
        FormField skills = byLabel(f, "Skills");

        Outcome exact = guard().fill(skills, "Java");

        assertThat(exact.done()).isTrue();
        assertThat(page.locator("#sk").inputValue()).isEqualTo("Java");
        assertThat(page.evaluate("window.__picked")).isEqualTo("Java");
        assertThat(clicks()).containsExactly("Java");

        Outcome partial = guard().fill(skills, "Kotl");
        assertThat(partial.done()).isFalse();
        assertThat(partial.reason()).contains("pick it yourself");
        assertThat(submitted()).isFalse();
    }

    @Test
    void clicksOnlyTheNecessaryOnlyCookieButton() {
        open("cookie_necessary.html");

        boolean clicked = guard().dismissCookieBanner();

        assertThat(clicked).isTrue();
        assertThat(page.evaluate("window.__cookie")).isEqualTo("necessary");
        assertThat(clicks()).containsExactly("Endast nödvändiga");
        assertThat(submitted()).isFalse();
    }

    @Test
    void leavesABannerThatOnlyOffersAcceptAllAlone() {
        open("cookie_all_only.html");

        assertThat(guard().dismissCookieBanner()).isFalse();

        assertThat(page.evaluate("window.__cookie")).isNull();
        assertThat(clicks()).isEmpty();
    }

    @Test
    void neverClicksSubmitApplyOrConfirmButtonsWhateverThePurpose() {
        for (String[] c : new String[][] {{"ponty.html", "button[type=submit]"}, {"ponty.html", "#applynow"},
                {"jobshark_modal.html", "button:has-text('Apply Now')"}, {"platsbanken_ad.html", "#ansok"},
                {"cookie_necessary.html", "#confirm"}, {"cookie_necessary.html", "form button"}, {"varbi_form.html", "button[type=submit]"},
                {"varbi_login.html", "button[type=submit]"}}) {
            open(c[0]);
            Locator target = page.locator(c[1]).first();
            for (ClickPurpose purpose : ClickPurpose.values()) {
                Outcome o = guard().click(target, purpose);
                assertThat(o.done()).as(c[0] + " " + c[1] + " " + purpose).isFalse();
            }
            assertThat(submitted()).as(c[0] + " " + c[1]).isFalse();
            assertThat(clicks()).as(c[0] + " " + c[1]).isEmpty();
        }
    }

    @Test
    void refusesToClickAnythingThatIsNotASuggestionForTheAutocompletePurpose() {
        open("workday.html");

        Outcome o = guard().click(page.locator("button:has-text('Next')"), ClickPurpose.AUTOCOMPLETE_OPTION);

        assertThat(o.done()).isFalse();
        assertThat(clicks()).isEmpty();
    }

    @Test
    void refusesAFieldThatDisappearedWhenThePageRerendered() throws Exception {
        var f = fields("rerender.html");

        assertThat(guard().fill(byLabel(f, "Förnamn"), "Cai").done()).isTrue();
        page.waitForFunction("document.querySelector('[data-jp-id]') === null");

        Outcome o = guard().fill(byLabel(f, "Efternamn"), "Birch");
        assertThat(o.done()).isFalse();
        assertThat(o.reason()).contains("gone");
    }

    @Test
    void worksInsideAnIframeAndStillRefusesConsentThere() {
        var f = fields("iframe_host.html");

        assertThat(guard().fill(byLabel(f, "Namn"), "Cai Birch").done()).isTrue();
        assertThat(page.frames().get(1).locator("#n").inputValue()).isEqualTo("Cai Birch");
        assertThat(guard().check(byLabel(f, "samtycker"), true).done()).isFalse();
        assertThat(page.frames().get(1).locator("#c").isChecked()).isFalse();
    }

    @Test
    void refusesUnsafeFieldIds() {
        FormField evil = new FormField("jp-1\"], body, [x=\"", 0, "input", "text", "", "Namn", "", "", "", "", "", "", "", false, false, true, "",
                false, false, "", false, List.of());
        open("ponty.html");

        assertThat(guard().fill(evil, "x").reason()).isEqualTo("unsafe field id");
    }

    @Test
    void aFieldWhoseTypeChangedToPasswordIsRefused() {
        var f = fields("ponty.html");
        FormField name = byLabel(f, "Namn");
        page.evaluate("document.getElementById('n').type = 'password'");

        Outcome o = guard().fill(name, "x");

        assertThat(o.done()).isFalse();
        assertThat(o.reason()).contains("password");
    }
}
