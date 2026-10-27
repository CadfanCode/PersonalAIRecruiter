package se.caibirch.jobpilot.apply;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;

import org.springframework.core.io.ClassPathResource;

/**
 * The only place that calls {@code evaluate}. It runs two fixed, read-only scripts from the classpath; no script
 * is ever built from Claude output, page text or user input (a source-scan test enforces this).
 */
public final class PageScripts {

    /** What a page looks like to the sign-in detector. */
    public record PageState(boolean passwordVisible, boolean signInText, boolean bankIdText, boolean createAccountText,
                            boolean confirmPasswordText, int fieldCount, boolean ansokButton, boolean sessionError,
                            String url, String title) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String EXTRACT = read("apply/extract-fields.js");
    private static final String DETECT = read("apply/detect-state.js");

    private PageScripts() {
    }

    /** All fields of the page and its frames. Ids of fields inside a frame are prefixed {@code f<n>-}. */
    public static List<FormField> extract(Page page) {
        List<FormField> all = new ArrayList<>();
        List<Frame> frames = page.frames();
        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            if (frame.isDetached()) {
                continue;
            }
            Object raw;
            try {
                raw = frame.evaluate(EXTRACT, i == 0 ? "" : "f" + i + "-");
            } catch (RuntimeException e) {
                continue; // a frame that navigated or is not scriptable has nothing to read
            }
            List<FormField> fields = MAPPER.convertValue(raw, new TypeReference<List<FormField>>() { });
            for (FormField f : fields) {
                all.add(f.withFrame(i));
            }
        }
        return all;
    }

    public static PageState detect(Page page) {
        Object raw = page.mainFrame().evaluate(DETECT);
        Map<String, Object> m = MAPPER.convertValue(raw, new TypeReference<Map<String, Object>>() { });
        int fields = ((Number) m.getOrDefault("fieldCount", 0)).intValue();
        // iframes can hold the whole form (SPEC 9.4 notes): count their fields too
        List<Frame> frames = page.frames();
        for (int i = 1; i < frames.size(); i++) {
            try {
                Map<String, Object> fm = MAPPER.convertValue(frames.get(i).evaluate(DETECT), new TypeReference<Map<String, Object>>() { });
                fields += ((Number) fm.getOrDefault("fieldCount", 0)).intValue();
            } catch (RuntimeException ignored) {
                // not scriptable
            }
        }
        return new PageState(b(m, "passwordVisible"), b(m, "signInText"), b(m, "bankIdText"), b(m, "createAccountText"),
                b(m, "confirmPasswordText"), fields, b(m, "ansokButton"), b(m, "sessionError"),
                String.valueOf(m.get("url")), String.valueOf(m.get("title")));
    }

    private static boolean b(Map<String, Object> m, String key) {
        return Boolean.TRUE.equals(m.get(key));
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
