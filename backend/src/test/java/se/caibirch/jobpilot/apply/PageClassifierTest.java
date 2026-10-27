package se.caibirch.jobpilot.apply;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import se.caibirch.jobpilot.apply.PageClassifier.Kind;
import se.caibirch.jobpilot.apply.PageScripts.PageState;

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
    void anAdPageWithOnlyAnAnsokButtonNeedsTheUser() {
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
}
