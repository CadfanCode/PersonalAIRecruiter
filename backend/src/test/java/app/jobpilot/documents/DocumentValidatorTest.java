package app.jobpilot.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import app.jobpilot.documents.DocumentModel.*;
import app.jobpilot.documents.ValidationContext.Fact;

class DocumentValidatorTest {

    DocumentValidator validator = new DocumentValidator();

    // entry 1 and 2 are selected, entry 3 is not
    Map<Long, Fact> facts = Map.of(
            10L, new Fact(10, 1, "Built microservices in Java and Kotlin.", "Byggde mikrotjänster i Java och Kotlin."),
            11L, new Fact(11, 1, "Fixed race conditions in the logging flows.", null),
            20L, new Fact(20, 2, "Wrote a REST API with 15 Flyway migrations.", "Skrev ett REST API med 15 Flyway-migreringar."),
            30L, new Fact(30, 3, "Taught PE.", "Undervisade i idrott."));

    ValidationContext ctx(boolean newGrad) {
        return new ValidationContext("sv", newGrad, facts, Set.of(1L, 2L), Set.of("Java", "Kotlin", "Spring Boot", "Flyway"),
                List.of("brinner för"), Set.of());
    }

    static Item item(long entryId, Bullet... bullets) {
        return new Item(entryId, "Titel", "Org", "Stockholm", "2026", List.of(bullets));
    }

    CvContent cv(String language, Section... sections) {
        return new CvContent(language, "Javautvecklare", List.of("Systemutvecklare med Java."), List.of(sections), "Svenska", null);
    }

    List<String> codes(Report r) {
        return r.violations().stream().map(Violation::code).toList();
    }

    @Test
    void aCleanCvPasses() {
        var cv = cv("sv", new Section("Erfarenhet", "EXPERIENCE", List.of(
                item(1, new Bullet("Byggde mikrotjänster i Java och Kotlin.", List.of(10L))),
                item(2, new Bullet("Skrev ett REST API med 15 Flyway-migreringar.", List.of(20L))))));

        assertThat(validator.validateCv(cv, ctx(false)).violations()).isEmpty();
    }

    @Test
    void bulletsWithoutCitationsFail() {
        var cv = cv("sv", new Section("Erfarenhet", "EXPERIENCE", List.of(item(1, new Bullet("Gjorde något.", List.of())))));

        var r = validator.validateCv(cv, ctx(false));

        assertThat(codes(r)).containsExactly("no_citation");
        assertThat(r.hasFailures()).isTrue();
        assertThat(r.violations().get(0).where()).isEqualTo("sections[0].items[0].bullets[0]");
    }

    @Test
    void unknownFactsAndFactsOfUnselectedEntriesFail() {
        var cv = cv("sv", new Section("Erfarenhet", "EXPERIENCE", List.of(item(1,
                new Bullet("Hittat på.", List.of(999L)), new Bullet("Undervisade.", List.of(30L))))));

        var r = validator.validateCv(cv, ctx(false));

        assertThat(codes(r)).containsExactly("unknown_fact", "unselected_entry");
        assertThat(r.hasFailures()).isTrue();
    }

    @Test
    void anItemForAnUnselectedEntryFails() {
        var cv = cv("sv", new Section("Erfarenhet", "EXPERIENCE", List.of(item(3, new Bullet("Undervisade.", List.of(10L))))));

        assertThat(codes(validator.validateCv(cv, ctx(false)))).contains("unselected_entry");
    }

    @Test
    void aFactFromAnotherSelectedEntryOnlyWarns() {
        var cv = cv("sv", new Section("Erfarenhet", "EXPERIENCE", List.of(item(1, new Bullet("Skrev ett REST API.", List.of(20L))))));

        var r = validator.validateCv(cv, ctx(false));

        assertThat(codes(r)).containsExactly("fact_from_other_entry");
        assertThat(r.hasFailures()).isFalse();
    }

    @Test
    void numbersMissingFromTheCitedFactsWarn() {
        var cv = cv("sv", new Section("Erfarenhet", "EXPERIENCE", List.of(item(2,
                new Bullet("Skrev ett REST API med 15 Flyway-migreringar och 99 000 användare.", List.of(20L))))));

        var r = validator.validateCv(cv, ctx(false));

        assertThat(r.violations()).extracting(Violation::code, Violation::text, Violation::severity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("number_not_in_facts", "99 000", Severity.WARN));
    }

    @Test
    void skillsNotInTheSkillTableOrFactsWarn() {
        var skills = new Section("Tekniska kunskaper", "SKILLS", List.of(
                new Item(0, "Java och backend", "Java, Kotlin, Spring Boot (v3, v4), Flyway-migreringar, Haskell, bygger tjänster från databas till drift i produktion", null, "", List.of())));

        var r = validator.validateCv(cv("sv", skills), ctx(false));

        assertThat(r.violations()).extracting(Violation::code, Violation::text).containsExactly(
                org.assertj.core.groups.Tuple.tuple("unknown_skill", "Haskell"));
    }

    @Test
    void theDocumentLanguageMustMatchTheAd() {
        var r = validator.validateCv(cv("en"), ctx(false));

        assertThat(codes(r)).containsExactly("wrong_language");
        assertThat(r.hasFailures()).isTrue();
    }

    @Test
    void styleRulesApplyToTheWholeCvIncludingJavaFields() {
        var cv = new CvContent("sv", "Javautvecklare – backend", List.of("Jag brinner för kod."), List.of(), "Svenska", "Övrig anställning 2019–2021");

        var r = validator.validateCv(cv, ctx(false));

        assertThat(r.violations()).extracting(Violation::where).containsExactlyInAnyOrder("headline", "gapNote", "profileParagraphs[0]");
    }

    @Test
    void lettersNeedCitationsAndValidFacts() {
        var ok = new LetterContent("sv", "Ansökan: Dev", "Acme, Stockholm", "Hej,", List.of("Jag är systemutvecklare."),
                "Vänliga hälsningar,", "Cai Birch", List.of(10L));
        assertThat(validator.validateLetter(ok, ctx(false)).violations()).isEmpty();

        var none = new LetterContent("sv", "A", "B", "Hej,", List.of("Text"), "Hälsningar", "Cai", List.of());
        var bad = new LetterContent("sv", "A", "B", "Hej,", List.of("Text"), "Hälsningar", "Cai", List.of(30L, 404L));
        assertThat(codes(validator.validateLetter(none, ctx(false)))).containsExactly("no_citation");
        assertThat(codes(validator.validateLetter(bad, ctx(false)))).containsExactly("unselected_entry", "unknown_fact");
    }

    @Test
    void lettersFlagInventedNumbersAndBadOpenings() {
        var letter = new LetterContent("sv", "A", "B", "Hej,", List.of("Jag heter Cai och har 12 års erfarenhet."),
                "Hälsningar", "Cai", List.of(10L));

        var r = validator.validateLetter(letter, ctx(false));

        assertThat(codes(r)).containsExactlyInAnyOrder("number_not_in_facts", "bad_opening");
        assertThat(r.maxPages()).isEqualTo(1);
    }

    @Test
    void aCvWhoseBulletsRepeatTheFactsWarnsOnceButANewlyWrittenOneDoesNot() {
        var copied = cv("sv", new Section("Erfarenhet", "EXPERIENCE", List.of(
                item(1, new Bullet("Byggde mikrotjänster i Java och Kotlin.", List.of(10L)),
                        new Bullet("Fixed race conditions in the logging flows.", List.of(11L))),
                item(2, new Bullet("Skrev ett REST API med 15 Flyway-migreringar.", List.of(20L)),
                        new Bullet("Skrev ett REST API med 15 Flyway-migreringar.", List.of(20L))))));
        var digested = cv("sv", new Section("Erfarenhet", "EXPERIENCE", List.of(
                item(1, new Bullet("Rättade race conditions i loggflödena och drev mikrotjänster i Java och Kotlin i produktion.", List.of(10L, 11L))),
                item(2, new Bullet("Byggde ett REST API där 15 versionerade Flyway-migreringar håller databasen i takt.", List.of(20L))),
                item(2, new Bullet("Skrev ett REST API med 15 Flyway-migreringar.", List.of(20L))),
                item(2, new Bullet("Ett till.", List.of(20L))))));

        var warned = validator.validateCv(copied, ctx(false));
        assertThat(codes(warned).stream().filter("copied_facts"::equals)).hasSize(1);
        assertThat(warned.hasFailures()).as("copying only warns").isFalse();
        assertThat(codes(validator.validateCv(digested, ctx(false)))).doesNotContain("copied_facts");
    }
}
