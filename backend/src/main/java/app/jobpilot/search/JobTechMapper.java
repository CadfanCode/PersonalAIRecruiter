package app.jobpilot.search;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import app.jobpilot.jobs.LanguageDetector;
import app.jobpilot.jobs.NewJob;

/** Maps one JobTech hit to a {@link NewJob} (SPEC 6.1 field list). */
public final class JobTechMapper {

    /** JobTech sends local Swedish times without a zone. */
    static final ZoneId SWEDEN = ZoneId.of("Europe/Stockholm");
    private static final Pattern REMOTE = Pattern.compile("distans|remote", Pattern.CASE_INSENSITIVE);

    private JobTechMapper() {
    }

    /** Empty for ads the API marks as removed or that lack a headline. */
    public static Optional<NewJob> map(JsonNode hit) {
        if (hit.path("removed").asBoolean(false) || text(hit, "headline") == null) {
            return Optional.empty();
        }
        JsonNode address = hit.path("workplace_address");
        String municipality = text(address, "municipality");
        String city = text(address, "city");
        String employer = text(hit.path("employer"), "name");
        String workplace = text(hit.path("employer"), "workplace");
        String clientEmployer = clientEmployer(employer, workplace, municipality);
        String description = text(hit.path("description"), "text");
        String hours = text(hit.path("working_hours_type"), "label");
        String employment = hours != null ? hours : text(hit.path("employment_type"), "label");
        String workplaceModel = text(hit.path("workplace_model"), "label");

        return Optional.of(new NewJob("JOBTECH", text(hit, "id"), text(hit, "webpage_url"),
                text(hit.path("application_details"), "url"), text(hit, "headline"), employer, clientEmployer,
                city != null ? city : municipality, municipality, instant(text(hit, "publication_date")),
                instant(text(hit, "application_deadline")), LanguageDetector.detect(description), employment,
                description, hit.toString(), false, null, hit.path("relevance").asDouble(0),
                workplaceModel != null && REMOTE.matcher(workplaceModel).find()));
    }

    private static final java.util.Set<String> GENERIC = java.util.Set.of("ab", "aktiebolag", "publ", "ltd", "inc", "gmbh", "sverige",
            "sweden", "nordic", "group", "holding", "the", "och", "and", "&");

    /**
     * The end client is the workplace only when it names someone else. "SAAB Aktiebolag" with workplace "Saab", a
     * workplace that is just the city, or one whose words are contained in the employer's are the same employer.
     */
    static String clientEmployer(String employer, String workplace, String municipality) {
        if (workplace == null || employer == null) {
            return null;
        }
        String w = workplace.strip();
        if (w.isEmpty() || (municipality != null && w.equalsIgnoreCase(municipality.strip()))) {
            return null;
        }
        java.util.Set<String> e = words(employer);
        java.util.Set<String> c = words(w);
        if (c.isEmpty() || e.containsAll(c) || c.containsAll(e)) {
            return null;
        }
        return w;
    }

    private static java.util.Set<String> words(String name) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String t : name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!t.isEmpty() && !GENERIC.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value).atZone(SWEDEN).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
