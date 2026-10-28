package se.caibirch.jobpilot.cvdb;

import java.math.BigDecimal;
import java.util.List;

/** Types of the CV database as exposed by the REST API. Tags are carried by name. */
public final class CvModel {

    private CvModel() {
    }

    public enum Kind {
        EDUCATION, WORK, INTERNSHIP, PROJECT, OWN_BUSINESS, VOLUNTEER, CERTIFICATION, LICENCE, COURSE, AWARD, PUBLICATION,
        CONFERENCE, LANGUAGE, OTHER
    }

    public record Language(String language, String level) {
    }

    public record Profile(Long id, String fullName, String headline, String email, String phone,
                          String phoneInternational, String city, String website, String github, String linkedin,
                          String workAuthorisation, List<Language> languages) {
    }

    public record Fact(Long id, Long entryId, String textEn, String textSv, String evidence, String metrics,
                       int sortOrder, List<String> tags) {
    }

    public record Entry(Long id, Kind kind, String title, String titleEn, String organisation, String location,
                        String startYm, String endYm, boolean ongoing, String summary, String note, int sortOrder,
                        boolean hidden, List<String> tags, List<Fact> facts) {
    }

    /** Writable part of an entry. Facts are changed only through their own endpoints so fact ids stay stable. */
    public record EntryInput(Kind kind, String title, String titleEn, String organisation, String location,
                             String startYm, String endYm, Boolean ongoing, String summary, String note,
                             Integer sortOrder, Boolean hidden, List<String> tags) {
    }

    public record FactInput(String textEn, String textSv, String evidence, String metrics, Integer sortOrder,
                            List<String> tags) {
    }

    public record Skill(Long id, String name, String category, String level, BigDecimal years, String lastUsedYm) {
    }

    public record SkillInput(String name, String category, String level, BigDecimal years, String lastUsedYm) {
    }

    public record Tag(Long id, String name) {
    }

    public record KnownGap(Long id, String text, int sortOrder) {
    }

    public record Answer(Long id, String questionKey, String question, String answer) {
    }
}
