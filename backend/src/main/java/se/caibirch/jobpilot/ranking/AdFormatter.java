package se.caibirch.jobpilot.ranking;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import se.caibirch.jobpilot.jobs.JobRepository.AdText;

/** Renders a stored ad as plain text for the scoring prompt, including JobTech's structured requirements. */
final class AdFormatter {

    static final int MAX_DESCRIPTION_CHARS = 12_000;

    private AdFormatter() {
    }

    static String format(AdText ad, ObjectMapper mapper) {
        StringBuilder out = new StringBuilder();
        line(out, "Title", ad.title());
        line(out, "Employer", ad.employer());
        line(out, "Location", ad.municipality());
        line(out, "Published", ad.publishedAt() == null ? "unknown" : ad.publishedAt().toString());
        line(out, "Application deadline", ad.deadline() == null ? "none stated" : ad.deadline().toString());
        JsonNode raw = parse(ad.rawJson(), mapper);
        if (raw != null) {
            if (raw.has("experience_required")) {
                line(out, "Experience required (flag in the ad register)", raw.get("experience_required").asText());
            }
            requirements(out, "Must have", raw.path("must_have"));
            requirements(out, "Nice to have", raw.path("nice_to_have"));
        }
        out.append("\nDescription:\n");
        String d = ad.description() == null ? "" : ad.description();
        out.append(d.length() > MAX_DESCRIPTION_CHARS ? d.substring(0, MAX_DESCRIPTION_CHARS) + "\n[cut]" : d);
        return out.toString();
    }

    private static void requirements(StringBuilder out, String label, JsonNode node) {
        List<String> items = new ArrayList<>();
        for (String group : List.of("skills", "languages", "work_experiences", "education", "education_level")) {
            for (JsonNode n : node.path(group)) {
                String l = n.path("label").asText("");
                if (!l.isBlank()) {
                    items.add(group.replace('_', ' ') + ": " + l);
                }
            }
        }
        if (!items.isEmpty()) {
            line(out, label, String.join("; ", items));
        }
    }

    private static void line(StringBuilder out, String label, String value) {
        if (value != null && !value.isBlank()) {
            out.append(label).append(": ").append(value).append('\n');
        }
    }

    private static JsonNode parse(String json, ObjectMapper mapper) {
        try {
            return json == null ? null : mapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
