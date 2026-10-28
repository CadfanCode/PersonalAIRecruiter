package app.jobpilot.claude;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;

/** Loads schemas and prompt templates from {@code resources/claude/} (SPEC 5.2). */
public final class ClaudeResources {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private ClaudeResources() {
    }

    /** An example text under {@code claude/examples/}, used as a shape reference in prompts. */
    public static String example(String name) {
        return read("claude/examples/" + name + ".txt").strip();
    }

    public static String schema(String name) {
        return read("claude/schemas/" + name + ".json");
    }

    /** Reads {@code claude/prompts/<name>.md} and replaces {@code {{key}}} with the given values. */
    public static String prompt(String name, Map<String, String> values) {
        String template = read("claude/prompts/" + name + ".md");
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null) {
                throw new IllegalArgumentException("No value for placeholder " + m.group() + " in prompt " + name);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read classpath resource " + path, e);
        }
    }
}
