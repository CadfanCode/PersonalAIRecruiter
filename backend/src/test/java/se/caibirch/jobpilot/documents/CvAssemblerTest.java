package se.caibirch.jobpilot.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import se.caibirch.jobpilot.cvdb.CvModel.Entry;
import se.caibirch.jobpilot.cvdb.CvModel.Kind;
import se.caibirch.jobpilot.cvdb.CvModel.Language;
import se.caibirch.jobpilot.cvdb.CvModel.Profile;
import se.caibirch.jobpilot.documents.DocumentModel.*;
import se.caibirch.jobpilot.documents.Selection.SelectedEntry;

class CvAssemblerTest {

    Entry braive = new Entry(1L, Kind.INTERNSHIP, "Systemutvecklare (praktik)", "Software Developer (intern)", "Braive AB",
            "Stockholm", "2026-01", "2026-05", false, null, null, 0, false, List.of(), List.of());
    Entry kv = new Entry(2L, Kind.WORK, "Kriminalvårdare", "Prison Officer", "Kriminalvården", "Stockholm", "2025-06", null, true,
            null, null, 1, false, List.of(), List.of());
    Entry yh = new Entry(5L, Kind.EDUCATION, "Yrkeshögskoleexamen, mjukvaruutveckling med Java", "Higher Vocational Diploma, Software Development with Java",
            "YH Akademin", "Sundsvall", "2024", "2026", false, null, null, 2, false, List.of(), List.of());
    Map<Long, Entry> entries = Map.of(1L, braive, 2L, kv, 5L, yh);

    Profile profile = new Profile(1L, "Cai Birch", null, null, null, null, null, null, null, null, null,
            List.of(new Language("English", "native"), new Language("Swedish", "fluent")));

    Selection selection = new Selection(List.of(new SelectedEntry(1, List.of(10L), "both", "r"),
            new SelectedEntry(5, List.of(), "both", "Education is always included")));

    CvContent written(Item... items) {
        return new CvContent("sv", "Head", List.of("Profil"),
                List.of(new Section("Erfarenhet", "EXPERIENCE", List.of(items))), null, null);
    }

    @Test
    void overwritesEveryFactualHeaderFieldFromTheDatabase() {
        var claudeItem = new Item(1, "Hittepå AB-konsult", "Fantasy Corp", "Paris", "1999–2030",
                List.of(new Bullet("Byggde.", List.of(10L))));

        var out = CvAssembler.assemble(written(claudeItem), selection, entries, profile, "sv", "gap");

        Item item = out.sections().get(0).items().get(0);
        assertThat(item.title()).isEqualTo("Systemutvecklare (praktik)");
        assertThat(item.meta()).isEqualTo("Braive AB");
        assertThat(item.location()).isEqualTo("Stockholm");
        assertThat(item.dateLabel()).isEqualTo("2026");
        assertThat(item.bullets()).extracting(Bullet::text).containsExactly("Byggde.");
        assertThat(out.gapNote()).isEqualTo("gap");
    }

    @Test
    void englishDocumentsUseEnglishTitles() {
        var out = CvAssembler.assemble(written(new Item(1, "", "", null, "", List.of())), selection, entries, profile, "en", null);

        assertThat(out.sections().get(0).items().get(0).title()).isEqualTo("Software Developer (intern)");
        assertThat(CvAssembler.title(kv, "en")).isEqualTo("Prison Officer");
        assertThat(CvAssembler.title(new Entry(9L, Kind.WORK, "Bara svenska", null, null, null, null, null, false, null, null, 0, false, List.of(), List.of()), "en"))
                .isEqualTo("Bara svenska");
    }

    @Test
    void skillsItemsWithoutAnEntryAreLeftAlone() {
        var skills = new Section("Tekniska kunskaper", "SKILLS", List.of(new Item(0, "Java och backend", "Java, Kotlin", null, "", List.of())));
        var content = new CvContent("sv", "H", List.of(), List.of(skills), null, null);

        var out = CvAssembler.assemble(content, selection, entries, profile, "sv", null);

        assertThat(out.sections().get(0).items().get(0).meta()).isEqualTo("Java, Kotlin");
    }

    @Test
    void aSelectedEducationEntryClaudeLeftOutIsAddedWithoutBullets() {
        var out = CvAssembler.assemble(written(new Item(1, "", "", null, "", List.of())), selection, entries, profile, "sv", null);

        Section education = out.sections().stream().filter(s -> s.kind().equals("EDUCATION")).findFirst().orElseThrow();
        assertThat(education.heading()).isEqualTo("Utbildning");
        assertThat(education.items().get(0).title()).startsWith("Yrkeshögskoleexamen");
        assertThat(education.items().get(0).dateLabel()).isEqualTo("2024-2026");
        assertThat(education.items().get(0).bullets()).isEmpty();
    }

    @Test
    void anExistingEducationSectionGetsTheMissingEntryAppended() {
        var section = new Section("Utbildning", "EDUCATION", List.of(new Item(99, "Annat", "X", null, "", List.of())));
        var content = new CvContent("en", "H", List.of(), List.of(section), null, null);

        var out = CvAssembler.assemble(content, selection, entries, profile, "en", null);

        assertThat(out.sections()).hasSize(1);
        assertThat(out.sections().get(0).items()).hasSize(2);
        assertThat(out.sections().get(0).items().get(1).title()).startsWith("Higher Vocational Diploma");
    }

    @Test
    void languagesLineListsTheAdsLanguageFirstWithLocalisedLevels() {
        assertThat(CvAssembler.languagesLine(profile.languages(), "sv")).isEqualTo("Svenska (flytande) och engelska (modersmål)");
        assertThat(CvAssembler.languagesLine(profile.languages(), "en")).isEqualTo("English (native) and Swedish (fluent)");
        assertThat(CvAssembler.languagesLine(List.of(), "sv")).isNull();
        assertThat(CvAssembler.languagesLine(List.of(new Language("English", "native")), "en")).isEqualTo("English (native)");
        assertThat(CvAssembler.languagesLine(profile.languages(), "sv")).doesNotContain("–");
    }
}
