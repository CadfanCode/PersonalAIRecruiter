package app.jobpilot.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import app.jobpilot.cvdb.CvModel.Entry;
import app.jobpilot.cvdb.CvModel.Fact;
import app.jobpilot.cvdb.CvModel.Kind;
import app.jobpilot.documents.Selection.SelectedEntry;

class FactSelectionEnforcementTest {

    static Fact fact(long id, long entry) {
        return new Fact(id, entry, "en " + id, "sv " + id, null, null, 0, List.of());
    }

    static Entry entry(long id, Kind kind, Fact... facts) {
        return new Entry(id, kind, "T" + id, null, "Org", null, null, null, false, null, null, 0, false, List.of(), List.of(facts));
    }

    // 'usable' is what the service passes in: hidden and excluded entries are already removed
    List<Entry> usable = List.of(entry(1, Kind.WORK, fact(10, 1), fact(11, 1)), entry(2, Kind.PROJECT, fact(20, 2)),
            entry(5, Kind.EDUCATION, fact(50, 5), fact(51, 5)), entry(7, Kind.OTHER, fact(70, 7)));

    static SelectedEntry sel(long id, String use, Long... facts) {
        return new SelectedEntry(id, List.of(facts), use, "r");
    }

    @Test
    void keepsClaudesOrderAndFactOrder() {
        var out = FactSelectionService.enforce(new Selection(List.of(sel(2, "both", 20L), sel(1, "cv", 11L, 10L))), usable, Set.of());

        assertThat(out.entries()).extracting(SelectedEntry::entryId).containsExactly(2L, 1L, 5L);
        assertThat(out.entries().get(1).factIds()).containsExactly(11L, 10L);
    }

    @Test
    void dropsUnknownHiddenAndExcludedEntriesAndFactsOfOtherEntries() {
        var raw = new Selection(List.of(sel(1, "cv", 10L, 20L, 999L, 10L), sel(404, "both", 1L), sel(3, "both", 30L)));

        var out = FactSelectionService.enforce(raw, usable, Set.of());

        assertThat(out.entries()).extracting(SelectedEntry::entryId).containsExactly(1L, 5L);
        assertThat(out.entries().get(0).factIds()).as("only facts of that entry, once").containsExactly(10L);
    }

    @Test
    void pinnedEntriesAreForcedInWithAllTheirFacts() {
        var out = FactSelectionService.enforce(new Selection(List.of(sel(1, "cv", 10L))), usable, Set.of(2L, 7L));

        assertThat(out.entries()).extracting(SelectedEntry::entryId).contains(2L, 7L);
        SelectedEntry pinnedOther = out.entries().stream().filter(e -> e.entryId() == 7).findFirst().orElseThrow();
        assertThat(pinnedOther.use()).isEqualTo("both");
        assertThat(pinnedOther.reason()).isEqualTo("Pinned by the user");
    }

    @Test
    void aPinnedEntryClaudeChoseFewFactsFromGetsAllOfThem() {
        var out = FactSelectionService.enforce(new Selection(List.of(sel(1, "letter", 10L))), usable, Set.of(1L));

        SelectedEntry e = out.entries().stream().filter(x -> x.entryId() == 1).findFirst().orElseThrow();
        assertThat(e.factIds()).containsExactly(10L, 11L);
        assertThat(e.use()).isEqualTo("both");
    }

    @Test
    void educationIsAlwaysIncludedAndNeverLetterOnly() {
        var none = FactSelectionService.enforce(new Selection(List.of(sel(1, "both", 10L))), usable, Set.of());
        assertThat(none.entries().stream().filter(e -> e.entryId() == 5).findFirst().orElseThrow().factIds()).containsExactly(50L, 51L);

        var letterOnly = FactSelectionService.enforce(new Selection(List.of(sel(1, "both", 10L), sel(5, "letter", 50L))), usable, Set.of());
        SelectedEntry edu = letterOnly.entries().stream().filter(e -> e.entryId() == 5).findFirst().orElseThrow();
        assertThat(edu.use()).isEqualTo("both");
        assertThat(edu.factIds()).containsExactly(50L);
    }

    @Test
    void anExcludedEducationEntryStaysOut() {
        List<Entry> withoutEducation = usable.stream().filter(e -> e.id() != 5).toList();

        var out = FactSelectionService.enforce(new Selection(List.of(sel(1, "both", 10L))), withoutEducation, Set.of());

        assertThat(out.entries()).extracting(SelectedEntry::entryId).containsExactly(1L);
    }

    @Test
    void duplicateEntriesAreMergedAndUnknownUseBecomesBoth() {
        var out = FactSelectionService.enforce(new Selection(List.of(sel(1, "weird", 10L), sel(1, "cv", 11L))), usable, Set.of());

        SelectedEntry e = out.entries().get(0);
        assertThat(e.factIds()).containsExactly(10L, 11L);
        assertThat(e.use()).isEqualTo("both");
    }

    @Test
    void letterOnlyEntriesStayOutOfTheCv() {
        var out = FactSelectionService.enforce(new Selection(List.of(sel(1, "both", 10L), sel(7, "letter", 70L))), usable, Set.of());

        assertThat(out.forCv()).extracting(SelectedEntry::entryId).containsExactly(1L, 5L);
        assertThat(out.forLetter()).extracting(SelectedEntry::entryId).containsExactly(1L, 7L, 5L);
    }

    @Test
    void anEmptySelectionWithNoEducationFails() {
        List<Entry> noEducation = usable.stream().filter(e -> e.kind() != Kind.EDUCATION).toList();

        assertThatThrownBy(() -> FactSelectionService.enforce(new Selection(List.of(sel(404, "both"))), noEducation, Set.of()))
                .hasMessageContaining("No CV entries were selected");
    }
}
