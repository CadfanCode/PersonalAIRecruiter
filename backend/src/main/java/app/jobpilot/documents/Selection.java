package app.jobpilot.documents;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** The facts chosen for one job: which entries and facts, and whether each goes into the CV, the letter or both. */
public record Selection(List<SelectedEntry> entries) {

    public record SelectedEntry(long entryId, List<Long> factIds, String use, String reason) {
    }

    public List<SelectedEntry> forCv() {
        return entries.stream().filter(e -> !"letter".equals(e.use())).toList();
    }

    public List<SelectedEntry> forLetter() {
        return entries.stream().filter(e -> !"cv".equals(e.use())).toList();
    }

    public static Set<Long> ids(List<SelectedEntry> entries) {
        return entries.stream().map(SelectedEntry::entryId).collect(Collectors.toSet());
    }
}
