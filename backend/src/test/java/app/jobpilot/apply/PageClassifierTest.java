package app.jobpilot.apply;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import app.jobpilot.apply.PageClassifier.Kind;
import app.jobpilot.apply.PageScripts.PageState;

class PageClassifierTest {

    static PageState state(boolean password, boolean signIn, boolean bankId, boolean createAccount, boolean confirmPw,
                           int fields, boolean ansok, boolean error) {
        return new PageState(password, signIn, bankId, createAccount, confirmPw, fields, ansok, error, "http://x", "t");
    }

    @Test
    void aVisiblePasswordFieldAlwaysMeansSignIn() {
        assertThat(PageClassifier.classify(state(true, false, false, false, false, 12, false, false))).isEqualTo(Kind.WAITING_FOR_SIGN_IN);
    }

    @Test
    void bankIdAndAccountCreationWaitForTheUser() {
        assertThat(PageClassifier.classify(state(false, false, true, false, false, 1, false, false))).isEqualTo(Kind.WAITING_FOR_SIGN_IN);
        assertThat(PageClassifier.classify(state(false, false, false, true, false, 1, false, false))).isEqualTo(Kind.WAITING_FOR_SIGN_IN);
        assertThat(PageClassifier.classify(state(false, false, false, false, true, 5, false, false))).isEqualTo(Kind.WAITING_FOR_SIGN_IN);
    }

    @Test
    void aLogInLinkInTheHeaderOfARealFormIsNotAWall() {
        assertThat(PageClassifier.classify(state(false, true, false, false, false, 8, false, false))).isEqualTo(Kind.FORM);
        assertThat(PageClassifier.classify(state(false, true, false, false, false, 1, false, false))).isEqualTo(Kind.WAITING_FOR_SIGN_IN);
    }

    @Test
    void anAdPageWithOnlyAnApplyButtonIsFollowedNotWaitedOn() {
        assertThat(PageClassifier.classify(state(false, false, false, false, false, 0, true, false))).isEqualTo(Kind.MANUAL_APPLY);
        assertThat(PageClassifier.classify(state(false, false, false, false, false, 0, false, false))).isEqualTo(Kind.EMPTY);
    }

    @Test
    void sessionErrorsAreRecognisedFirst() {
        assertThat(PageClassifier.classify(state(false, false, false, false, false, 3, false, true))).isEqualTo(Kind.SESSION_ERROR);
    }

    @Test
    void aPlainFormIsAForm() {
        assertThat(PageClassifier.classify(state(false, false, false, false, false, 6, true, false))).isEqualTo(Kind.FORM);
    }

    // What the real pages looked like (inspected read-only on 2026-10-28).

    @Test
    void theReachMeeAdIsNotAWallJustBecauseItSaysRegisterkontroll() {
        // fieldCount 0, an ANSÖK link, no sign-in text; the old pattern matched "register" inside "registerkontroll"
        var ad = state(false, false, false, false, false, 0, true, false);
        assertThat(PageClassifier.classify(ad)).isEqualTo(Kind.MANUAL_APPLY);
        // and the very same page as the old detector saw it, with the createAccount flag wrongly set, still has a way on
        var asOldDetectorSawIt = state(false, false, false, true, false, 0, true, false);
        PageClassifier.Classification c = PageClassifier.explain(asOldDetectorSawIt);
        assertThat(c.kind()).isEqualTo(Kind.MANUAL_APPLY);
        assertThat(c.reasons()).anyMatch(r -> r.contains("not a wall"));
    }

    @Test
    void theReachMeeApplyFormIsAFormEvenThoughItMentionsBelastningsregister() {
        var form = state(false, false, false, true, false, 45, false, false);
        PageClassifier.Classification c = PageClassifier.explain(form);
        assertThat(c.kind()).isEqualTo(Kind.FORM);
        assertThat(c.reasons()).anyMatch(r -> r.contains("45 fields"));
    }

    @Test
    void aTeamtailorAdWithALogInLinkInTheHeaderAndAnApplyButtonIsNotAWall() {
        assertThat(PageClassifier.classify(state(false, true, false, false, false, 0, true, false))).isEqualTo(Kind.MANUAL_APPLY);
    }

    @Test
    void aVarbiAdWhoseOnlyWayOnIsLoggingInIsAWallAndSaysWhy() {
        PageClassifier.Classification c = PageClassifier.explain(state(false, true, false, false, false, 0, false, false));
        assertThat(c.kind()).isEqualTo(Kind.WAITING_FOR_SIGN_IN);
        assertThat(c.reasons()).anyMatch(r -> r.contains("no way on without signing in"));
    }

    @Test
    void aFormThatOffersBankIdAsOneOptionIsStillAForm() {
        assertThat(PageClassifier.classify(state(false, false, true, false, false, 12, false, false))).isEqualTo(Kind.FORM);
    }

    @Test
    void aShortFormOfTwoFieldsIsAFormEvenThoughItsOwnButtonSaysAnsok() {
        assertThat(PageClassifier.classify(state(false, false, false, false, false, 2, true, false))).isEqualTo(Kind.FORM);
    }

    @Test
    void everyClassificationSaysWhy() {
        assertThat(PageClassifier.explain(state(true, false, false, false, false, 9, false, false)).reasons()).containsExactly("a password field is visible");
        assertThat(PageClassifier.explain(state(false, false, false, false, false, 0, false, false)).reasons()).isNotEmpty();
    }
}
