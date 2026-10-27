package se.caibirch.jobpilot.apply;

import java.util.regex.Pattern;

/** The text rules of SPEC 9.3. Consent and click rules are deliberately broad: a false refusal costs one manual click. */
public final class GuardPatterns {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** Checkboxes and radios about consent, terms, privacy, newsletters or talent pools: left for the user. */
    public static final Pattern CONSENT = Pattern.compile(
            "samtyck|consent|gdpr|villkor|terms|privacy|integritet|talent|stay in touch|nyhetsbrev|newsletter"
                    + "|godkänn|accept|personuppgift|policy|intyga|marknadsför|marketing|prenumer|subscribe", FLAGS);

    /** Buttons and links that send, submit, apply or confirm. Never clicked by any code path. */
    public static final Pattern SUBMIT_LIKE = Pattern.compile(
            "skicka|send|submit|apply now|ansök|slutför|bekräfta|confirm|finish", FLAGS);

    /** The only banner buttons that may be clicked: decline everything but the necessary. */
    public static final Pattern COOKIE_NECESSARY = Pattern.compile(
            "nödvändiga|necessary|reject|endast|avvisa|neka|decline|essential|only required|bara nödvändiga", FLAGS);

    /** Login, BankID and account-creation words in labels and values: such fields are never touched. */
    public static final Pattern CREDENTIAL = Pattern.compile("password|lösenord|bankid|personnummer|pin ?code|engångskod|otp", FLAGS);

    private GuardPatterns() {
    }

    public static boolean isConsent(String text) {
        return text != null && CONSENT.matcher(text).find();
    }

    public static boolean isSubmitLike(String text) {
        return text != null && SUBMIT_LIKE.matcher(text).find();
    }
}
