package app.jobpilot.documents;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import app.jobpilot.cvdb.CvModel.Entry;
import app.jobpilot.cvdb.CvModel.Kind;

/**
 * Keeps employment gaps honest (SPEC 8.1): when leaving a job out would leave more than six months
 * of working life unexplained, the CV gets one line such as "Övrig anställning 2019-2021, referenser
 * lämnas på begäran". The line is built here from database dates, never by Claude.
 */
public final class GapNote {

    private static final Set<Kind> EMPLOYMENT = EnumSet.of(Kind.WORK, Kind.INTERNSHIP, Kind.OWN_BUSINESS);
    static final int GAP_MONTHS = 6;

    private GapNote() {
    }

    private record Span(int from, int to) {
        int months() {
            return to - from + 1;
        }
    }

    public static Optional<String> compute(List<Entry> all, Set<Long> selectedIds, String language, YearMonth now) {
        List<Span> covered = new ArrayList<>();
        for (Entry e : all) {
            if (selectedIds.contains(e.id())) {
                span(e, now).ifPresent(covered::add);
            }
        }
        List<int[]> years = new ArrayList<>();
        for (Entry e : all) {
            if (e.hidden() || selectedIds.contains(e.id())) {
                continue;
            }
            Optional<Span> span = span(e, now);
            if (span.isPresent() && uncovered(span.get(), covered) > GAP_MONTHS) {
                years.add(new int[] {span.get().from() / 12, span.get().to() / 12});
            }
        }
        if (years.isEmpty()) {
            return Optional.empty();
        }
        String ranges = merge(years).stream().map(r -> r[0] == r[1] ? String.valueOf(r[0]) : r[0] + "-" + r[1])
                .collect(Collectors.joining(", "));
        boolean sv = "sv".equals(language);
        int comma = ranges.lastIndexOf(", ");
        if (comma > 0) {
            ranges = ranges.substring(0, comma) + (sv ? " och " : " and ") + ranges.substring(comma + 2);
        }
        return Optional.of(sv ? "Övrig anställning " + ranges + ", referenser lämnas på begäran"
                : "Other employment " + ranges + ", available on request");
    }

    /** Omitted periods that overlap or touch (at most a year apart) are shown as one range; distant ones stay separate. */
    private static List<int[]> merge(List<int[]> years) {
        List<int[]> sorted = new ArrayList<>(years);
        sorted.sort(Comparator.comparingInt(r -> r[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] r : sorted) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && r[0] <= last[1] + 1) {
                last[1] = Math.max(last[1], r[1]);
            } else {
                merged.add(new int[] {r[0], r[1]});
            }
        }
        return merged;
    }

    private static Optional<Span> span(Entry e, YearMonth now) {
        if (!EMPLOYMENT.contains(e.kind()) || e.startYm() == null) {
            return Optional.empty();
        }
        int from = index(e.startYm(), 1);
        int to = e.ongoing() ? now.getYear() * 12 + now.getMonthValue() - 1 : e.endYm() == null ? -1 : index(e.endYm(), 12);
        return to < from ? Optional.empty() : Optional.of(new Span(from, to));
    }

    /** Months since year 0; "2019" is read as January when it starts a span and as December when it ends one. */
    private static int index(String ym, int defaultMonth) {
        int year = Integer.parseInt(ym.substring(0, 4));
        int month = ym.length() >= 7 ? Integer.parseInt(ym.substring(5, 7)) : defaultMonth;
        return year * 12 + month - 1;
    }

    private static int uncovered(Span s, List<Span> covered) {
        int n = 0;
        for (int m = s.from(); m <= s.to(); m++) {
            final int month = m;
            if (covered.stream().noneMatch(c -> month >= c.from() && month <= c.to())) {
                n++;
            }
        }
        return n;
    }
}
