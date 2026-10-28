package app.jobpilot.apply;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Which link or button on an ad page opens the application form. Pure logic over what the read-only candidate script returned,
 * so it is unit-testable; the click or navigation itself happens in {@link BrowserGuard}.
 */
public final class ApplyCandidates {

    /** One visible link or button of the page, as {@code find-apply-candidates.js} reports it. */
    public record Candidate(int index, String tag, String type, String role, String text, String href, String target,
                            boolean visible, boolean inFormWithFields, boolean inForm) {

        boolean anchorWithAddress() {
            return "a".equals(tag) && usableHref();
        }

        boolean usableHref() {
            if (href == null || href.isBlank()) {
                return false;
            }
            String h = href.toLowerCase(Locale.ROOT);
            return h.startsWith("http://") || h.startsWith("https://");
        }
    }

    private ApplyCandidates() {
    }

    /** Whether the candidate may be considered at all: an apply label, no final-submit word, not a form control. */
    static boolean eligible(Candidate c) {
        if (!GuardPatterns.APPLY_LABEL.matcher(c.text()).find() || GuardPatterns.isFinalSubmit(c.text())) {
            return false;
        }
        if ("submit".equals(c.type()) || c.inFormWithFields()) {
            return false;
        }
        if (c.href() != null && !c.href().isBlank() && !c.usableHref()) {
            return false; // mailto:, tel:, javascript: and in-page anchors are not a way to a form
        }
        return true;
    }

    /** The best candidate: a real link to an address beats a button, an address that looks like an application beats others. */
    public static Optional<Candidate> pick(List<Candidate> all) {
        return all.stream().filter(ApplyCandidates::eligible)
                .max(Comparator.comparingInt(ApplyCandidates::score).thenComparing(Comparator.comparingInt(Candidate::index).reversed()));
    }

    static int score(Candidate c) {
        int score = 1;
        if (c.anchorWithAddress()) {
            score += 10;
            String h = c.href().toLowerCase(Locale.ROOT);
            if (h.matches(".*(/apply|/ansok|/application|/ansokan|applications/|/sok).*")) {
                score += 4;
            }
        }
        if (c.text().toLowerCase(Locale.ROOT).startsWith("ansök") || c.text().toLowerCase(Locale.ROOT).startsWith("apply")) {
            score += 2;
        }
        return score;
    }
}
