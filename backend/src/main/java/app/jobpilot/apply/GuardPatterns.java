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

    /**
     * The whole label of a button that only moves a multi-step form on (or takes the guest route past an account page). The label
     * must be nothing but this: "Next", "Nästa steg", "Continue to documents", "Apply manually", "Continue as guest". Anything with a
     * {@link #FINAL_SUBMIT} word is refused on top of this, and the guard refuses it on a review page (ADR 0023).
     */
    public static final Pattern ADVANCE_LABEL = Pattern.compile(
            "^\\s*(next( step| page)?|continue|continue to [\\p{L} ]{1,40}|save (and|&) (continue|next)|go (on|to the next step)"
                    + "|nästa( steg| sida)?|nästa:? [\\p{L} ]{1,40}|fortsätt|fortsätt till [\\p{L} ]{1,40}|spara och fortsätt|gå vidare|vidare"
                    + "|apply manually|ansök manuellt|continue as (a )?guest|fortsätt som gäst|(apply|continue|ansök|fortsätt|proceed)"
                    + " (without|utan) [\\p{L} ]{1,30})\\s*[>»→›]*\\s*$", FLAGS);

    /** Controls that send an application. If one is on the page, a Next/Continue there may be the last step: it is not pressed. */
    public static final Pattern SENDS = Pattern.compile(
            "submit|skicka in|skicka ansökan|send (in )?(my |the )?application|apply now|ansök nu|slutför|finish|complete application"
                    + "|bekräfta och skicka|confirm and (send|submit)|registrera ansökan|lämna in", FLAGS);

    /** A heading or step name of a page that summarises the application before it is sent. */
    public static final Pattern REVIEW_PAGE = Pattern.compile(
            "review|granska|sammanfattning|summary|översikt|overview|check your|kontrollera|confirm|bekräfta|förhandsgransk|preview", FLAGS);

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

    /** A control that sends or finishes: a sending word anywhere, or a bare "Ansök" / "Apply" (the button that sends on a last step). */
    public static boolean sendsApplication(String label) {
        return label != null && (SENDS.matcher(label).find() || FINAL_SUBMIT.matcher(label).find()
                || label.strip().matches("(?iu)(ansök|apply|sök|ansök här|apply here)\\s*[>»→›]*"));
    }

    public static boolean isAdvance(String label) {
        return label != null && ADVANCE_LABEL.matcher(label.strip()).find() && !FINAL_SUBMIT.matcher(label).find();
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
