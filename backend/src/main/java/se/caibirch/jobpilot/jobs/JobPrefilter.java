package se.caibirch.jobpilot.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import se.caibirch.jobpilot.search.SearchProfile;

/** Java pre-filter before any model call (SPEC 7.1): age, deadline, exclusions and location. */
public final class JobPrefilter {

    public record Dropped(NewJob job, String reason) {
    }

    public record Result(List<NewJob> kept, List<Dropped> dropped) {

        /** Why ads were dropped, as "3 excluded term, 1 deadline passed", most frequent first. */
        public String dropSummary() {
            Map<String, Long> counts = dropped.stream()
                    .collect(Collectors.groupingBy(d -> category(d.reason()), LinkedHashMap::new, Collectors.counting()));
            return counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
        }

        private static String category(String reason) {
            if (reason.startsWith("older than")) return "too old";
            if (reason.startsWith("deadline")) return "deadline passed";
            if (reason.startsWith("excluded term")) return "excluded term";
            if (reason.startsWith("outside locations")) return "outside locations";
            return "other";
        }
    }

    private record Exclusion(String term, Pattern pattern) {
    }

    private static final Pattern REMOTE = Pattern.compile("distans|remote|hemarbete|arbeta hemifrån|work from home",
            Pattern.CASE_INSENSITIVE);

    private JobPrefilter() {
    }

    public static Result apply(List<NewJob> jobs, SearchProfile profile, Instant now) {
        Instant oldest = now.minus(Duration.ofDays(profile.maxAgeDays()));
        Set<String> places = profile.locations().stream()
                .flatMap(l -> java.util.stream.Stream.of(l.municipality(), l.name()))
                .map(JobPrefilter::lower).collect(Collectors.toSet());
        List<Exclusion> exclusions = profile.exclusions().stream().map(String::trim).filter(e -> !e.isEmpty())
                .map(e -> new Exclusion(e, Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(e) + "(?![\\p{L}\\p{N}])",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))).toList();

        List<NewJob> kept = new ArrayList<>();
        List<Dropped> dropped = new ArrayList<>();
        for (NewJob job : jobs) {
            String reason = reason(job, profile, oldest, now, places, exclusions);
            if (reason == null) {
                kept.add(job);
            } else {
                dropped.add(new Dropped(job, reason));
            }
        }
        return new Result(kept, dropped);
    }

    private static String reason(NewJob job, SearchProfile profile, Instant oldest, Instant now, Set<String> places,
                                 List<Exclusion> exclusions) {
        if (job.publishedAt() != null && job.publishedAt().isBefore(oldest)) {
            return "older than " + profile.maxAgeDays() + " days";
        }
        if (job.deadline() != null && job.deadline().isBefore(now)) {
            return "deadline has passed";
        }
        String titleAndEmployer = (job.title() == null ? "" : job.title()) + " | "
                + (job.employer() == null ? "" : job.employer());
        for (Exclusion e : exclusions) {
            if (e.pattern().matcher(titleAndEmployer).find()) {
                return "excluded term '" + e.term() + "'";
            }
        }
        if (!places.isEmpty() && job.municipality() != null && !inPlaces(job.municipality(), places)) {
            boolean remote = profile.remoteOk() && (job.remoteHint()
                    || REMOTE.matcher(job.title() + " " + (job.descriptionText() == null ? "" : job.descriptionText())).find());
            if (!remote) {
                return "outside locations (" + job.municipality() + ")";
            }
        }
        return null;
    }

    /** "Nacka" matches "Nacka kommun" and "Nacka, Sweden", which free-text web ads use. */
    private static boolean inPlaces(String municipality, Set<String> places) {
        String m = lower(municipality);
        return places.stream().anyMatch(p -> !p.isEmpty() && (m.equals(p) || m.contains(p)));
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
