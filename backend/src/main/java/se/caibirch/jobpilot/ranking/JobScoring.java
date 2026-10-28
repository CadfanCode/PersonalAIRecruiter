package se.caibirch.jobpilot.ranking;

import java.time.Instant;
import java.util.List;

import se.caibirch.jobpilot.jobs.JobModel.Score;

/** Turns Claude's rubric answer into a stored score. The total is always computed here (SPEC 5.4). */
public final class JobScoring {

    public static final int SKILLS_MAX = 50;
    public static final int EXPERIENCE_MAX = 30;
    public static final int ELIGIBILITY_MAX = 20;
    static final int TEXT_MAX = 300;

    /** Claude's answer for the {@code scoreJob} task. The *Max fields are echoed back and ignored. */
    public record Raw(int skills, int skillsMax, int experience, int experienceMax, int eligibility, int eligibilityMax,
                      boolean asksForNewGraduates, String experienceRequiredText, String language, String fit,
                      String gaps, List<String> dealBreakers) {
    }

    private JobScoring() {
    }

    public static Score toScore(Raw raw, String model, Instant now) {
        int skills = clamp(raw.skills(), SKILLS_MAX);
        int experience = clamp(raw.experience(), EXPERIENCE_MAX);
        int eligibility = clamp(raw.eligibility(), ELIGIBILITY_MAX);
        return new Score(skills + experience + eligibility, skills, experience, eligibility,
                raw.dealBreakers() == null ? List.of() : raw.dealBreakers(), truncate(raw.fit()),
                truncate(raw.gaps()), model, now);
    }

    static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    /** Cuts at a word boundary when possible and marks the cut. */
    static String truncate(String text) {
        if (text == null || text.length() <= TEXT_MAX) {
            return text;
        }
        String cut = text.substring(0, TEXT_MAX - 1);
        int space = cut.lastIndexOf(' ');
        return (space > TEXT_MAX / 2 ? cut.substring(0, space) : cut) + "…";
    }
}
