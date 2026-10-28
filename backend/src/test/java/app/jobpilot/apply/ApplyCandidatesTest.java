package app.jobpilot.apply;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import app.jobpilot.apply.ApplyCandidates.Candidate;

class ApplyCandidatesTest {

    static Candidate link(int index, String text, String href) {
        return new Candidate(index, "a", "", "", text, href, "", true, false, false);
    }

    static Candidate button(int index, String text, String type, boolean inFormWithFields) {
        return new Candidate(index, "button", type, "", text, "", "", true, inFormWithFields, inFormWithFields);
    }

    @Test
    void theRealReachMeeAnsokLinkIsChosenOverShareAndPolicyLinks() {
        List<Candidate> page = List.of(link(0, "Skicka till en vän", ""), link(1, "Dela", ""), link(2, "här", "https://example.invalid/arsoversikt.pdf"),
                link(3, "ANSÖK", "https://web103.reachmee.com/ext/I005/333/apply?site=2&job_id=50928"));
        assertThat(ApplyCandidates.pick(page)).get().extracting(Candidate::index).isEqualTo(3);
    }

    @Test
    void aRealLinkBeatsAButtonAndTheFirstOfTwoEqualLinksWins() {
        List<Candidate> page = List.of(button(0, "Apply for this job", "", false), link(1, "Ansök", "https://x.example/apply"),
                link(2, "Ansök", "https://x.example/apply"));
        assertThat(ApplyCandidates.pick(page)).get().extracting(Candidate::index).isEqualTo(1);
    }

    @Test
    void jobylonsInterestedLinkIsAnApplyLinkAndItsMailtoLinkIsNot() {
        List<Candidate> page = List.of(link(0, "Send e-mail", "mailto:a@b.se"), link(1, "I'm interested", "https://emp.example/applications/jobs/1/create/"),
                link(2, "Ansök via e-post", "mailto:a@b.se"));
        assertThat(ApplyCandidates.pick(page)).get().extracting(Candidate::index).isEqualTo(1);
    }

    @Test
    void nothingThatSendsOrIsPartOfAFormIsEligible() {
        assertThat(ApplyCandidates.pick(List.of(button(0, "Ansök", "submit", false)))).isEmpty();
        assertThat(ApplyCandidates.pick(List.of(button(0, "Ansök", "", true)))).as("a button inside a form with fields").isEmpty();
        assertThat(ApplyCandidates.pick(List.of(button(0, "Skicka ansökan", "", false), button(1, "Ansök och skicka", "", false)))).isEmpty();
        assertThat(ApplyCandidates.pick(List.of(link(0, "Ansök", "javascript:go()")))).isEmpty();
        assertThat(ApplyCandidates.pick(List.of(link(0, "Share", "https://x.example/share")))).isEmpty();
    }

    @Test
    void aButtonWithNoAddressIsAcceptedWhenItIsTheOnlyWayOn() {
        assertThat(ApplyCandidates.pick(List.of(button(4, "Apply for this job", "", false)))).get().extracting(Candidate::index).isEqualTo(4);
    }
}
