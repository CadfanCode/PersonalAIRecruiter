package app.jobpilot.apply;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GuardPatternsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Jag samtycker till behandling av mina personuppgifter", "I consent to the processing of my data", "GDPR",
            "Jag har läst villkoren", "I accept the terms and conditions", "Privacy policy", "Integritetspolicy",
            "Gå med i vår talent pool", "Join our talent community", "Stay in touch with news", "Ja, jag vill ha nyhetsbrev",
            "Subscribe to our newsletter", "Jag godkänner", "Jag intygar att uppgifterna stämmer", "Marknadsföring via e-post"})
    void consentLikeLabelsAreRecognised(String label) {
        assertThat(GuardPatterns.isConsent(label)).as(label).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Förnamn", "E-post", "Telefonnummer", "Ladda upp CV", "Har du erfarenhet av Java?", "City", "Cover letter",
            "Har du rätt att arbeta i Sverige?", "Message to the employer"})
    void ordinaryFieldsAreNotConsent(String label) {
        assertThat(GuardPatterns.isConsent(label)).as(label).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Skicka ansökan", "Send application", "Submit", "Apply now", "Ansök nu", "Ansök", "Slutför", "Bekräfta val",
            "Confirm choices", "Finish", "SKICKA"})
    void submitLikeButtonsAreRecognised(String text) {
        assertThat(GuardPatterns.isSubmitLike(text)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Endast nödvändiga", "Only necessary cookies", "Reject all", "Avvisa alla", "Neka", "Necessary only"})
    void necessaryOnlyCookieButtonsAreRecognised(String text) {
        assertThat(GuardPatterns.COOKIE_NECESSARY.matcher(text).find()).as(text).isTrue();
        assertThat(GuardPatterns.isSubmitLike(text)).as("not submit-like: " + text).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Acceptera alla", "Accept all", "Tillåt alla cookies", "Next", "Nästa"})
    void otherBannerButtonsAreNotNecessaryOnly(String text) {
        assertThat(GuardPatterns.COOKIE_NECESSARY.matcher(text).find()).as(text).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Lösenord", "Password", "Bekräfta lösenord", "BankID", "Personnummer", "Engångskod"})
    void credentialFieldsAreRecognised(String label) {
        assertThat(GuardPatterns.CREDENTIAL.matcher(label).find()).as(label).isTrue();
    }
}
