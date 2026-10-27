package se.caibirch.jobpilot.documents;

/** Year ranges for CV items, built from the database dates with plain hyphens (never en-dashes). */
public final class DateLabels {

    private DateLabels() {
    }

    /** "2026", "2018-2021", "2025-nu", "till 2026" or "" when there are no dates. */
    public static String label(String startYm, String endYm, boolean ongoing, String language) {
        String start = year(startYm);
        String end = year(endYm);
        boolean sv = "sv".equals(language);
        if (ongoing) {
            String now = sv ? "nu" : "present";
            return start == null ? now : start + "-" + now;
        }
        if (start != null && end != null) {
            return start.equals(end) ? start : start + "-" + end;
        }
        if (start != null) {
            return start;
        }
        if (end != null) {
            return (sv ? "till " : "until ") + end;
        }
        return "";
    }

    private static String year(String ym) {
        return ym == null || ym.length() < 4 ? null : ym.substring(0, 4);
    }
}
