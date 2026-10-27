package se.caibirch.jobpilot.documents;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import se.caibirch.jobpilot.cvdb.CvModel.Entry;
import se.caibirch.jobpilot.cvdb.CvModel.Kind;

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
        int minYear = Integer.MAX_VALUE;
        int maxYear = Integer.MIN_VALUE;
        for (Entry e : all) {
            if (e.hidden() || selectedIds.contains(e.id())) {
                continue;
            }
            Optional<Span> span = span(e, now);
            if (span.isPresent() && uncovered(span.get(), covered) > GAP_MONTHS) {
                minYear = Math.min(minYear, span.get().from() / 12);
                maxYear = Math.max(maxYear, span.get().to() / 12);
            }
        }
        if (minYear == Integer.MAX_VALUE) {
            return Optional.empty();
        }
        String range = minYear == maxYear ? String.valueOf(minYear) : minYear + "-" + maxYear;
        return Optional.of("sv".equals(language) ? "Övrig anställning " + range + ", referenser lämnas på begäran"
                : "Other employment " + range + ", available on request");
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
