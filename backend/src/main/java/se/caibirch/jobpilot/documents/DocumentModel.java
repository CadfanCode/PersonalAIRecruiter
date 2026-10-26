package se.caibirch.jobpilot.documents;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Content types of the generated documents (SPEC 5.4) and their validation report. */
public final class DocumentModel {

    private DocumentModel() {
    }

    public enum Kind { CV, LETTER }

    public record Bullet(String text, List<Long> factIds) {
    }

    /**
     * One entry of a CV section. {@code title}, {@code meta}, {@code location} and {@code dateLabel} are
     * filled by Java from the CV database after Claude has written (Claude supplies only the bullets), so
     * employers, places and dates cannot be invented.
     */
    public record Item(long entryId, String title, String meta, String location, String dateLabel, List<Bullet> bullets) {
    }

    public record Section(String heading, String kind, List<Item> items) {
    }

    /**
     * CV content. {@code languagesLine} and {@code gapNote} are written by Java, never by Claude.
     * Unknown JSON properties are ignored so older stored documents still load.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CvContent(String language, String headline, List<String> profileParagraphs, List<Section> sections,
                            String languagesLine, String gapNote) {

        public CvContent withJavaFields(String languagesLine, String gapNote, List<Section> sections) {
            return new CvContent(language, headline, profileParagraphs, sections, languagesLine, gapNote);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LetterContent(String language, String subject, String subline, String greeting,
                                List<String> paragraphs, String closing, String signature, List<Long> factIdsUsed) {
    }

    public enum Severity { FAIL, WARN }

    /** @param where a JSON-pointer-like location the UI can highlight, e.g. "paragraphs[0]" or "sections[1].items[0].bullets[2]" */
    public record Violation(String code, Severity severity, String where, String text, String message) {
    }

    public record Report(List<Violation> violations, int pages, int maxPages) {

        public boolean hasFailures() {
            return violations.stream().anyMatch(v -> v.severity() == Severity.FAIL);
        }
    }
}
