package app.jobpilot.apply;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import app.jobpilot.apply.BrowserGuard.Opened;

/** The agent's way through a form, against local fixtures with trap buttons. Nothing is ever sent. */
class MultiStepGuardTest extends PlaywrightTest {

    BrowserGuard guard() {
        return new BrowserGuard(page, 1_500, null);
    }

    PageSnapshot snap() {
        return PageScripts.snapshot(page);
    }

    String ref(PageSnapshot s, String role, String name) {
        return s.nodes().stream().filter(n -> n.role().equals(role) && n.name().equals(name) && n.ref() != null).map(PageSnapshot.Node::ref)
                .findFirst().orElseThrow(() -> new AssertionError(role + " '" + name + "' not in\n" + s.forPrompt()));
    }

    @SuppressWarnings("unchecked")
    List<Object> traps() {
        return (List<Object>) page.evaluate("window.__trap");
    }

    @Test
    void theSnapshotForThePromptNeverCarriesWhatATextBoxHolds() {
        open("secrets.html");
        guard().fill(snap().field(ref(snap(), "textbox", "Name")), "Cai Birch");
        PageSnapshot s = snap();

        assertThat(s.forPrompt()).doesNotContain("hunter2secret").doesNotContain("19900101-1234").doesNotContain("Cai Birch")
                .contains("textbox \"Password\"").contains("[filled]");
        // inside the process the value is still known, so "already has a value" works
        assertThat(s.field(ref(s, "textbox", "Name")).value()).isEqualTo("Cai Birch");
    }

    @Test
    void aPasswordFieldNamedByARefIsRefusedWhateverTheModelSays() {
        open("secrets.html");
        PageSnapshot s = snap();
        FormField password = s.field(ref(s, "textbox", "Password"));

        assertThat(guard().fill(password, "x").done()).isFalse();
        assertThat(page.locator("#pw").inputValue()).isEqualTo("hunter2secret");
    }

    @Test
    void aRefThatIsNotARefIsNeverTurnedIntoALocator() {
        open("multistep.html");
        FormField evil = new FormField("e1; body", 0, "input", "text", "", "x", "", "", "", "", "", "", "", false, false, true, "", false, false, "", false, List.of());

        assertThat(guard().fill(evil, "x").reason()).isEqualTo("unsafe field id");
        assertThat(guard().follow("e1\" >> body", false, u -> true).done()).isFalse();
    }

    @Test
    void aCustomDropDownIsOpenedAndItsOptionPicked() {
        open("multistep.html");
        PageSnapshot s = snap();

        var outcome = guard().choose(s.field(ref(s, "combobox", "Country")), "Sweden");

        assertThat(outcome.done()).as(String.valueOf(outcome.reason())).isTrue();
        assertThat(page.evaluate("window.__country")).isEqualTo("SE");
        assertThat(traps()).isEmpty();
    }

    @Test
    void anOptionThatDoesNotExistIsNeverInvented() {
        open("multistep.html");
        PageSnapshot s = snap();

        assertThat(guard().choose(s.field(ref(s, "combobox", "Country")), "Atlantis").done()).isFalse();
        assertThat(page.evaluate("window.__country")).isNull();
    }

    @Test
    void aNativeSelectIsChosenByItsLabel() {
        open("multistep.html");
        PageSnapshot s = snap();
        FormField lang = s.field(ref(s, "combobox", "Language"));

        assertThat(guard().choose(lang, "Swedish").done()).isTrue();
        assertThat(page.locator("#lang").inputValue()).isEqualTo("sv");
        assertThat(guard().choose(lang, "Klingon").done()).isFalse();
    }

    @Test
    void nextMovesOnThroughTheStepsAndStopsAtTheReviewPage() {
        open("multistep.html");
        Opened first = guard().follow(ref(snap(), "button", "Next"), true, u -> true);
        assertThat(first.done()).as(first.how()).isTrue();
        assertThat(page.locator("#step2").isVisible()).isTrue();

        Opened second = guard().follow(ref(snap(), "button", "Continue"), true, u -> true);
        assertThat(second.done()).as(second.how()).isTrue();
        assertThat(page.locator("#step3").isVisible()).isTrue();

        // the review page: its Continue may send, and the send button is a sending word
        Opened review = guard().follow(ref(snap(), "button", "Continue"), true, u -> true);
        assertThat(review.done()).isFalse();
        assertThat(review.how()).contains("review");
        Opened send = guard().follow(ref(snap(), "button", "Skicka in ansökan"), true, u -> true);
        assertThat(send.done()).isFalse();
        assertThat(traps()).isEmpty();
    }

    @Test
    void aContinueButtonNextToASendingButtonIsNotPressed() {
        open("multistep.html");
        page.evaluate("document.querySelector('h2').textContent = 'Your details'; document.getElementById('next1').insertAdjacentHTML('afterend', '<button type=button id=s>Submit application</button>')");

        Opened o = guard().follow(ref(snap(), "button", "Next"), true, u -> true);

        assertThat(o.done()).isFalse();
        assertThat(o.how()).contains("sends the application");
        assertThat(page.locator("#step1").isVisible()).isTrue();
    }

    @Test
    void onlyAnExactNextWordIsEverPressedAndAnApplyLabelNeverAfterFilling() {
        open("multistep.html");
        page.evaluate("document.getElementById('next1').textContent = 'Ansök nu'");
        assertThat(guard().follow(ref(snap(), "button", "Ansök nu"), true, u -> true).done()).isFalse();
        page.evaluate("document.getElementById('next1').textContent = 'Spara och skicka'");
        assertThat(guard().follow(ref(snap(), "button", "Spara och skicka"), false, u -> true).done()).isFalse();
        page.evaluate("document.getElementById('next1').textContent = 'Apply'");
        assertThat(guard().follow(ref(snap(), "button", "Apply"), true, u -> true).done()).as("anything was filled").isFalse();
        assertThat(guard().follow(ref(snap(), "button", "Apply"), false, u -> true).done()).as("the page already is a form").isFalse();
        assertThat(page.locator("#step1").isVisible()).isTrue();
    }

    @Test
    void anApplyButtonThatAppearsLateIsFoundAfterTheWaitAndALoginLinkInTheHeaderIsNotAWall() {
        open("late_apply.html");
        guard().awaitStable();
        PageSnapshot s = snap();

        assertThat(s.clickables()).extracting(PageSnapshot.Node::name).contains("Ansök");
        assertThat(PageClassifier.classify(PageScripts.detect(page))).isEqualTo(PageClassifier.Kind.MANUAL_APPLY);
    }

    @Test
    void theNextWordsAreExactAndSendingWordsNeverMatch() {
        for (String ok : new String[] {"Next", "Nästa", "Nästa steg", "Continue", "Fortsätt", "Gå vidare", "Continue to documents", "Apply manually", "Continue as guest", "Spara och fortsätt", "Next »"}) {
            assertThat(GuardPatterns.isAdvance(ok)).as(ok).isTrue();
        }
        for (String no : new String[] {"Submit", "Skicka in", "Send application", "Finish", "Confirm", "Bekräfta", "Register", "Registrera dig", "Apply now", "Ansök nu",
                "Continue and submit", "Fortsätt och skicka", "Next, then we send it", "Slutför", "Continue to submit application"}) {
            assertThat(GuardPatterns.isAdvance(no)).as(no).isFalse();
        }
    }
}
