package app.jobpilot.apply;

import java.util.regex.Pattern;

/** The text rules of SPEC 9.3. Consent and click rules are deliberately broad: a false refusal costs one manual click. */
public final class GuardPatterns {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** Checkboxes and radios about consent, terms, privacy, newsletters or talent pools: left for the user. */
    public static final Pattern CONSENT = Pattern.compile(
            "samtyck|consent|gdpr|villkor|terms|privacy|integritet|talent|stay in touch|nyhetsbrev|newsletter"
                    + "|godkänn|accept|personuppgift|policy|intyga|marknadsför|marketing|prenumer|subscribe", FLAGS);

    /** Words of the control that ends an application: sending, submitting, registering, confirming. Never clicked, whatever the purpose. */
    public static final Pattern FINAL_SUBMIT = Pattern.compile(
            "skicka|send|submit|slutför|bekräfta|confirm|finish|registrera|register|spara ansökan|save application|complete application", FLAGS);

    /**
     * Buttons and links that send, submit, apply or confirm. Only a link or button that opens the application form
     * ({@link BrowserGuard.ClickPurpose#OPEN_APPLICATION_FORM}) may carry "ansök" or "apply", and even that never together with
     * a {@link #FINAL_SUBMIT} word.
     */
    public static final Pattern SUBMIT_LIKE = Pattern.compile(FINAL_SUBMIT.pattern() + "|apply now|ansök", FLAGS);

    /** The labels of a link or button that opens an application form from an ad page. They start the label: "Ansök", "Apply for this job". */
    public static final Pattern APPLY_LABEL = Pattern.compile(
            "^\\s*(ansök|apply|sök (tjänsten|jobbet|nu)|jag är intresserad|i['’]?m interested|i am interested|start (the )?application"
                    + "|påbörja ansökan)", FLAGS);

    /** The only banner buttons that may be clicked: decline everything but the necessary. */
    public static final Pattern COOKIE_NECESSARY = Pattern.compile(
            "nödvändiga|necessary|reject|endast|avvisa|neka|decline|essential|only required|bara nödvändiga", FLAGS);

    /** Login, BankID and account-creation words in labels and values: such fields are never touched. */
    public static final Pattern CREDENTIAL = Pattern.compile("password|lösenord|bankid|personnummer|pin ?code|engångskod|otp", FLAGS);

    /** Personal identity numbers: never filled, never asked about in JobPilot, never shown to the model. Read from the nearby text too,
     *  because a field can have no label of its own (ReachMee's "last four digits" box sits under a "Personnummer" caption). */
    public static final Pattern PERSONAL_NUMBER = Pattern.compile(
            "personnummer|samordningsnummer|personal ?number|social security|\\bssn\\b|födelsenummer|birth ?last ?four", FLAGS);

    private GuardPatterns() {
    }

    public static boolean isConsent(String text) {
        return text != null && CONSENT.matcher(text).find();
    }

    public static boolean isSubmitLike(String text) {
        return text != null && SUBMIT_LIKE.matcher(text).find();
    }

    public static boolean isFinalSubmit(String text) {
        return text != null && FINAL_SUBMIT.matcher(text).find();
    }
}
