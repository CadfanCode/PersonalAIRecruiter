package app.jobpilot.manual;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * Turns an ad page into the plain text Claude reads: the structured data many sites publish (schema.org JobPosting in JSON-LD)
 * first, because it is exact, then the visible text without scripts, menus and footers.
 */
public final class AdText {

    static final int MAX_CHARS = 30_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AdText() {
    }

    public static String from(String html) {
        Document doc = Jsoup.parse(html);
        StringBuilder out = new StringBuilder();
        for (var script : doc.select("script[type=application/ld+json]")) {
            try {
                structured(MAPPER.readTree(script.data()), out);
            } catch (Exception e) {
                // not valid JSON-LD: the visible text below still carries the ad
            }
        }
        if (out.length() > 0) {
            out.append("\n--- Visible text of the page ---\n");
        }
        doc.select("script, style, noscript, nav, footer, header, svg, form, iframe").remove();
        String body = doc.body() == null ? "" : doc.body().wholeText();
        out.append(collapse(body));
        String text = out.toString().strip();
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
    }

    /** True when the text is long enough to be an ad rather than a login wall or a cookie notice. */
    public static boolean looksSubstantial(String text) {
        return text != null && text.strip().length() >= 400;
    }

    private static void structured(JsonNode node, StringBuilder out) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            node.forEach(n -> structured(n, out));
            return;
        }
        if (node.has("@graph")) {
            structured(node.get("@graph"), out);
        }
        JsonNode type = node.get("@type");
        boolean posting = type != null && (type.asText().equals("JobPosting")
                || (type.isArray() && toList(type).contains("JobPosting")));
        if (!posting) {
            return;
        }
        line(out, "Title", node.path("title").asText(""));
        line(out, "Employer", node.path("hiringOrganization").path("name").asText(""));
        JsonNode address = node.path("jobLocation").isArray() ? node.path("jobLocation").path(0).path("address") : node.path("jobLocation").path("address");
        line(out, "Location", String.join(", ", nonBlank(address.path("addressLocality").asText(""), address.path("addressRegion").asText(""),
                address.path("addressCountry").isObject() ? address.path("addressCountry").path("name").asText("") : address.path("addressCountry").asText(""))));
        line(out, "Employment type", node.path("employmentType").isArray() ? String.join(", ", toList(node.path("employmentType"))) : node.path("employmentType").asText(""));
        line(out, "Date posted", node.path("datePosted").asText(""));
        line(out, "Valid through (deadline)", node.path("validThrough").asText(""));
        line(out, "Apply link", node.path("url").asText(""));
        String description = node.path("description").asText("");
        if (!description.isBlank()) {
            out.append("Description:\n").append(collapse(Jsoup.parse(description).wholeText())).append('\n');
        }
    }

    private static void line(StringBuilder out, String label, String value) {
        if (value != null && !value.isBlank()) {
            out.append(label).append(": ").append(value).append('\n');
        }
    }

    private static List<String> toList(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<String> nonBlank(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }

    private static String collapse(String text) {
        return text.replace(' ', ' ').replaceAll("[ \\t]+", " ").replaceAll("(?m)^ +| +$", "").replaceAll("\\n{3,}", "\n\n").strip();
    }
}
