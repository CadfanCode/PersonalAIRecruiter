package app.jobpilot.apply;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.jobpilot.apply.BrowserGuard.ClickPurpose;
import app.jobpilot.apply.BrowserGuard.Outcome;

/** The aktivitetsrapport rules of the guard (SPEC 10), against fixtures only: never the real Arbetsförmedlingen. */
class AktivitetGuardTest extends PlaywrightTest {

    static final Path AKT = Path.of("src/test/resources/fixtures/aktivitet").toAbsolutePath();

    static String akt(String fixture) {
        return AKT.resolve(fixture).toUri().toString();
    }

    BrowserGuard guardFor(String fixture) {
        return new BrowserGuard(page, 5_000, akt(fixture));
    }

    void openAkt(String fixture) {
        page.navigate(akt(fixture));
    }

    Object eval(String script) {
        return page.evaluate(script);
    }

    @SuppressWarnings("unchecked")
    List<String> keys() {
        return (List<String>) eval("window.__keys");
    }

    // ---- Spara: every condition has its own test ----

    @Test
    void clicksSparaOnTheConfiguredFormAddress() {
        openAkt("form.html");

        Outcome o = guardFor("form.html").saveActivity();

        assertThat(o.done()).as(o.reason()).isTrue();
        assertThat(eval("(window.__saved || []).length")).isEqualTo(1);
        assertThat(eval("window.__skickaInClicked")).isEqualTo(Boolean.FALSE);
        assertThat(clicks()).containsExactly("Spara");
    }

    @Test
    void refusesSparaOnAnyOtherAddress() {
        openAkt("form.html");

        Outcome o = new BrowserGuard(page, 5_000, akt("form_li.html")).saveActivity();

        assertThat(o.done()).isFalse();
        assertThat(o.reason()).isEqualTo("refused: not on the aktivitetsrapport form");
        assertThat(eval("window.__saved === undefined")).isEqualTo(Boolean.TRUE);
        assertThat(clicks()).isEmpty();
    }

    @Test
    void refusesSparaWhenNoFormAddressIsConfigured() {
        openAkt("form.html");

        assertThat(new BrowserGuard(page).saveActivity().reason()).isEqualTo("refused: no aktivitet form address configured");
        assertThat(clicks()).isEmpty();
    }

    @Test
    void theQueryAndHashOfTheAddressDoNotMatter() {
        page.navigate(akt("form.html") + "?utm=x#top");

        assertThat(guardFor("form.html").saveActivity().done()).isTrue();
    }

    @Test
    void aButtonThatDoesNotReadExactlySparaIsNeverClicked() {
        for (String text : new String[] {"Spara och skicka in", "Spara utkast", "spara", "Skicka in", "Sparar..."}) {
            openAkt("form.html");
            page.evaluate("t => { document.getElementById('spara').textContent = t; }", text);

            Outcome o = guardFor("form.html").saveActivity();

            assertThat(o.done()).as(text).isFalse();
            assertThat(eval("window.__saved === undefined")).as(text).isEqualTo(Boolean.TRUE);
        }
        assertThat(clicks()).isEmpty();
    }

    @Test
    void skickaInIsRefusedEvenForTheSparaPurposeAndOnTheRightAddress() {
        openAkt("form.html");

        Outcome o = guardFor("form.html").click(page.locator("#skicka"), ClickPurpose.AKTIVITET_SPARA);

        assertThat(o.done()).isFalse();
        assertThat(eval("window.__skickaInClicked")).isEqualTo(Boolean.FALSE);
        for (ClickPurpose purpose : ClickPurpose.values()) {
            assertThat(guardFor("form.html").click(page.locator("#skicka"), purpose).done()).as(purpose.name()).isFalse();
        }
        assertThat(clicks()).isEmpty();
    }

    @Test
    void twoSparaButtonsAreRefusedBecauseTheFormLooksDifferent() {
        openAkt("form.html");
        page.evaluate("() => { var b = document.createElement('button'); b.type = 'button'; b.textContent = 'Spara'; document.body.appendChild(b); }");

        Outcome o = guardFor("form.html").saveActivity();

        assertThat(o.done()).isFalse();
        assertThat(o.reason()).isEqualTo("expected exactly one Spara button, found 2");
    }

    @Test
    void theSparaPurposeCannotClickAnythingElseEvenOnTheForm() {
        openAkt("form.html");

        Outcome o = guardFor("form.html").click(page.locator("input[value=heltid]"), ClickPurpose.AKTIVITET_SPARA);

        assertThat(o.done()).isFalse();
        assertThat(o.reason()).contains("does not read exactly Spara");
    }

    @Test
    void aSubmitTypeButtonIsStillRefusedForTheOtherPurposes() {
        openAkt("form.html");

        assertThat(guardFor("form.html").click(page.locator("#spara"), ClickPurpose.COOKIE_NECESSARY).done()).isFalse();
        assertThat(guardFor("form.html").click(page.locator("#spara"), ClickPurpose.AUTOCOMPLETE_OPTION).done()).isFalse();
        assertThat(eval("window.__saved === undefined")).isEqualTo(Boolean.TRUE);
    }

    // ---- autocomplete lists ----

    FormField field(String label) {
        return byLabel(PageScripts.extract(page), label);
    }

    @Test
    void picksTheExactSuggestionFromADelayedRoleOptionList() {
        openAkt("form.html");

        Outcome o = guardFor("form.html").typeAndPick(field("Yrkesroll"), "Systemutvecklare/Prog", "Systemutvecklare/Programmerare", false);

        assertThat(o.done()).as(o.reason()).isTrue();
        assertThat(eval("document.getElementById('yr').value")).isEqualTo("Systemutvecklare/Programmerare");
        assertThat(clicks()).containsExactly("Systemutvecklare/Programmerare");
        assertThat(keys()).as("typing is not a key press").isEmpty();
    }

    @Test
    void opensTheOrtListWithOneArrowDownAndPicksTheExactCity() {
        openAkt("form.html");

        Outcome o = guardFor("form.html").typeAndPick(field("Sök och välj ort"), "Stock", "Stockholm", true);

        assertThat(o.done()).as(o.reason()).isTrue();
        assertThat(eval("document.getElementById('ort').value")).isEqualTo("Stockholm");
        assertThat(keys()).containsExactly("ArrowDown");
        assertThat(clicks()).containsExactly("Stockholm");
    }

    @Test
    void aClosedListFromAnotherFieldDoesNotMaskTheOpenOne() {
        openAkt("form.html");
        BrowserGuard g = guardFor("form.html");
        assertThat(g.typeAndPick(field("Yrkesroll"), "Systemutvecklare/Prog", "Systemutvecklare/Programmerare", false).done()).isTrue();
        // the yrkesroll items are still in the page, hidden, ahead of the ort list in document order

        Outcome o = g.typeAndPick(field("Sök och välj ort"), "Solna", "Solna", true);

        assertThat(o.done()).as(o.reason()).isTrue();
        assertThat(eval("document.getElementById('ort').value")).isEqualTo("Solna");
    }

    @Test
    void worksWhenTheItemsAreRoleLessListItemsUnderAriaControls() {
        openAkt("form_li.html");

        Outcome o = guardFor("form_li.html").typeAndPick(field("Yrkesroll"), "Systemutvecklare/Prog", "Systemutvecklare/Programmerare", false);

        assertThat(o.done()).as(o.reason()).isTrue();
        assertThat(eval("document.getElementById('yr').value")).isEqualTo("Systemutvecklare/Programmerare");
    }

    @Test
    void withoutAnExactMatchNothingIsPickedAndTheJobWouldNotBeSaved() {
        openAkt("form.html");

        Outcome o = guardFor("form.html").typeAndPick(field("Yrkesroll"), "Syst", "Systemutveckl", false);

        assertThat(o.done()).isFalse();
        assertThat(o.reason()).contains("no suggestion matched exactly");
        assertThat(clicks()).isEmpty();
    }

    @Test
    void aShortWaitMissesLateSuggestionsAndSaysSo() {
        openAkt("form.html");

        Outcome o = new BrowserGuard(page, 100, akt("form.html")).typeAndPick(field("Yrkesroll"), "Systemutvecklare/Prog", "Systemutvecklare/Programmerare", false);

        assertThat(o.done()).isFalse();
        assertThat(o.reason()).contains("no suggestion appeared");
    }

    @Test
    void enterIsNeverPressedAndNoFormIsSubmittedWhileFilling() {
        openAkt("form.html");
        BrowserGuard g = guardFor("form.html");

        g.typeAndPick(field("Yrkesroll"), "Systemutvecklare/Prog", "Systemutvecklare/Programmerare", false);
        g.fill(field("Arbetsgivaren"), "Comstream AB");
        g.typeAndPick(field("Sök och välj ort"), "Stock", "Stockholm", true);

        assertThat(keys()).doesNotContain("Enter");
        assertThat(eval("window.__saved === undefined")).isEqualTo(Boolean.TRUE);
    }

    // ---- confirmation text and the saved list ----

    @Test
    void waitsForTheToastWithThePhraseAndTheEmployerName() {
        openAkt("form.html");
        page.evaluate("() => { document.getElementById('ag').value = 'Comstream AB'; }");
        guardFor("form.html").saveActivity();

        assertThat(guardFor("form.html").waitForConfirmation("Din aktivitet är sparad", List.of("Comstream AB"), 2_000)).isTrue();
        assertThat(guardFor("form.html").waitForConfirmation("Din aktivitet är sparad", List.of("Annan AB"), 600)).isFalse();
    }

    @Test
    void noToastMeansNoConfirmation() {
        openAkt("form_no_toast.html");
        guardFor("form_no_toast.html").saveActivity();

        assertThat(guardFor("form_no_toast.html").waitForConfirmation("Din aktivitet är sparad", List.of(""), 600)).isFalse();
    }

    @Test
    void readsSavedActivitiesWithIsoAndSwedishDates() {
        openAkt("list.html");

        var saved = PageScripts.savedActivities(page);

        assertThat(saved.noneMarker()).isFalse();
        assertThat(saved.entries()).extracting(PageScripts.SavedRow::date).contains("2026-10-26", "2026-10-25");
        assertThat(saved.entries().stream().filter(e -> e.date().equals("2026-10-26")).findFirst().orElseThrow().text()).contains("Comstream AB (Taxi Stockholm)");
    }

    @Test
    void anEmptyListIsRecognisedAndAnUnreadablePageIsNot() {
        openAkt("list_empty.html");
        var empty = PageScripts.savedActivities(page);
        assertThat(empty.entries()).isEmpty();
        assertThat(empty.noneMarker()).isTrue();

        openAkt("list_unreadable.html");
        var unreadable = PageScripts.savedActivities(page);
        assertThat(unreadable.entries()).isEmpty();
        assertThat(unreadable.noneMarker()).isFalse();
    }

    @Test
    void aLoginPageIsRecognisedAsAWall() {
        openAkt("login.html");

        assertThat(PageClassifier.classify(PageScripts.detect(page))).isEqualTo(PageClassifier.Kind.WAITING_FOR_SIGN_IN);
    }
}
