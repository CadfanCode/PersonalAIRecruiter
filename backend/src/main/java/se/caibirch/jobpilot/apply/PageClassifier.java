package se.caibirch.jobpilot.apply;

import se.caibirch.jobpilot.apply.PageScripts.PageState;

/** Decides what the user has to do before JobPilot can fill anything (SPEC 9.2 step 2). Read-only. */
public final class PageClassifier {

    public enum Kind {
        /** A form with fields: fill it. */
        FORM,
        /** A login, BankID or account-creation wall (hard rule 2): wait for the user. */
        WAITING_FOR_SIGN_IN,
        /** An ad page whose only way in is an "Ansök" button, which the app never clicks. */
        MANUAL_APPLY,
        /** "Something went wrong" or an expired session (Workday). */
        SESSION_ERROR,
        /** Nothing to fill and nothing to click. */
        EMPTY
    }

    private PageClassifier() {
    }

    public static Kind classify(PageState s) {
        if (s.sessionError()) {
            return Kind.SESSION_ERROR;
        }
        // A "Logga in" link in a header is on almost every page, so sign-in words only count on a nearly empty page.
        if (s.passwordVisible() || s.bankIdText() || s.confirmPasswordText() || (s.signInText() && s.fieldCount() <= 2)
                || (s.createAccountText() && s.fieldCount() <= 2)) {
            return Kind.WAITING_FOR_SIGN_IN;
        }
        if (s.fieldCount() > 0) {
            return Kind.FORM;
        }
        return s.ansokButton() ? Kind.MANUAL_APPLY : Kind.EMPTY;
    }
}
