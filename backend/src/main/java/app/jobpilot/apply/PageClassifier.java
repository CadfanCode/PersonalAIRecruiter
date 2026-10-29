package app.jobpilot.apply;

import java.util.ArrayList;
import java.util.List;

import app.jobpilot.apply.PageScripts.PageState;

/** Decides what the user has to do before JobPilot can fill anything (SPEC 9.2 step 2). Read-only. */
public final class PageClassifier {

    public enum Kind {
        /** A form with fields: fill it. */
        FORM,
        /** A login, BankID or account-creation wall (hard rule 2): wait for the user. */
        WAITING_FOR_SIGN_IN,
        /** An ad page with no form yet but a way in (an Apply link or button): the navigator follows it. */
        MANUAL_APPLY,
        /** "Something went wrong" or an expired session (Workday). */
        SESSION_ERROR,
        /** Nothing to fill and nothing to click. */
        EMPTY
    }

    /** The kind and the rules that decided it, so a wrong sign-in prompt can be debugged from the log and the card. */
    public record Classification(Kind kind, List<String> reasons) {
    }

    private PageClassifier() {
    }

    public static Kind classify(PageState s) {
        return explain(s).kind();
    }

    /**
     * Sign-in is only ever declared when no way to the form is visible: a login link in a header, an ad that mentions the word
     * "register" or BankID in its text, or a form that offers BankID as one option never counts on its own. A visible password
     * field (the page asks the person to sign in or create an account) always does.
     */
    public static Classification explain(PageState s) {
        List<String> why = new ArrayList<>();
        if (s.sessionError()) {
            why.add("the page says something went wrong or the session expired");
            return new Classification(Kind.SESSION_ERROR, why);
        }
        if (s.passwordVisible()) {
            why.add("a password field is visible");
            return new Classification(Kind.WAITING_FOR_SIGN_IN, why);
        }
        if (s.confirmPasswordText()) {
            why.add("the page asks to confirm a password (an account is being created)");
            return new Classification(Kind.WAITING_FOR_SIGN_IN, why);
        }
        boolean nearEmpty = s.fieldCount() <= 2;
        if (nearEmpty && !s.applyRoute() && (s.bankIdText() || s.signInText() || s.createAccountText())) {
            if (s.bankIdText()) {
                why.add("BankID is mentioned");
            }
            if (s.signInText()) {
                why.add("sign-in wording is on the page");
            }
            if (s.createAccountText()) {
                why.add("account-creation wording is on the page");
            }
            why.add("it has " + s.fieldCount() + " field(s) and no Apply link or button, so there is no way on without signing in");
            return new Classification(Kind.WAITING_FOR_SIGN_IN, why);
        }
        if (s.applyRoute() && s.fieldCount() == 0) {
            if (s.signInText() || s.bankIdText() || s.createAccountText()) {
                why.add("sign-in or account wording is on the page, but so is an Apply link or button, so it is not a wall");
            }
            why.add("an Apply link or button leads on from this page, which has no fields yet");
            return new Classification(Kind.MANUAL_APPLY, why);
        }
        if (s.fieldCount() > 0) {
            if (s.signInText() || s.bankIdText() || s.createAccountText()) {
                why.add("sign-in or account wording is on the page, but it holds " + s.fieldCount() + " fields, so it is a form");
            } else {
                why.add("the page holds " + s.fieldCount() + " fields");
            }
            return new Classification(Kind.FORM, why);
        }
        why.add("no fields and no Apply link or button");
        return new Classification(Kind.EMPTY, why);
    }
}
