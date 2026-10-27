package se.caibirch.jobpilot.apply;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import se.caibirch.jobpilot.claude.ClaudeTask;

/**
 * A deterministic stand-in for the model behind {@code mapFormFields}. It reads the fields JSON out of the prompt
 * and proposes actions by label, including the mistakes a real model could make (trying to tick consent boxes or
 * fill a password), so the tests prove the validator and the guard stop them.
 */
final class FakeFormMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeFormMapper() {
    }

    static String answer(ClaudeTask<?> task) {
        try {
            String prompt = task.prompt();
            JsonNode fields = MAPPER.readTree(block(prompt, 0));
            // the answers memory is the fifth JSON block of the prompt
            java.util.Map<String, String> memory = new java.util.HashMap<>();
            for (JsonNode m : MAPPER.readTree(block(prompt, 4))) {
                memory.put(se.caibirch.jobpilot.cvdb.AnswerKeys.normalise(m.get("question").asText()), m.get("answer").asText());
            }
            List<Map<String, Object>> actions = new ArrayList<>();
            List<Map<String, String>> questions = new ArrayList<>();
            for (JsonNode f : fields) {
                String id = f.get("id").asText();
                String type = f.get("type").asText();
                String text = (f.get("label").asText() + " " + f.get("question").asText() + " " + f.get("option").asText()).toLowerCase(Locale.ROOT);
                String tag = f.get("tag").asText();
                if (type.equals("email")) {
                    actions.add(act(id, "fill", "caibirch@proton.me", "none", "profile"));
                } else if (type.equals("tel")) {
                    actions.add(act(id, "fill", "076-185 95 27", "none", "profile"));
                } else if (text.contains("förnamn") || text.contains("first name")) {
                    actions.add(act(id, "fill", "Cai", "none", "profile"));
                } else if (text.contains("efternamn") || text.contains("last name")) {
                    actions.add(act(id, "fill", "Birch", "none", "profile"));
                } else if (f.get("label").asText().trim().equalsIgnoreCase("namn")) {
                    actions.add(act(id, "fill", "Cai Birch", "none", "profile"));
                } else if (type.equals("url")) {
                    actions.add(act(id, "fill", "https://www.caibirch.com", "none", "profile"));
                } else if (type.equals("file")) {
                    boolean letter = text.contains("övriga") || text.contains("brev") || text.contains("letter");
                    actions.add(act(id, "upload", "", letter ? "letter" : "cv", "document"));
                } else if (tag.equals("select") && text.contains("kategori")) {
                    actions.add(act(id, "select", "CV", "none", "document"));
                } else if (tag.equals("select") && text.contains("country")) {
                    actions.add(act(id, "select", "Sweden", "none", "profile"));
                } else if (type.equals("password")) {
                    actions.add(act(id, "fill", "hunter2", "none", "profile")); // a mistake the validator must stop
                } else if (type.equals("checkbox") || (type.equals("radio") && text.contains("terms"))) {
                    actions.add(act(id, "check", "", "none", "cv")); // another mistake: ticking consent
                } else if (type.equals("radio") && text.contains("rätt att arbeta")) {
                    actions.add(act(id, "check", f.get("option").asText(), "none", "memory"));
                } else if (type.equals("radio") && memory.containsKey(se.caibirch.jobpilot.cvdb.AnswerKeys.normalise(f.get("question").asText()))) {
                    String answer = memory.get(se.caibirch.jobpilot.cvdb.AnswerKeys.normalise(f.get("question").asText()));
                    actions.add(answer.equalsIgnoreCase(f.get("option").asText())
                            ? act(id, "check", answer, "none", "memory") : skip(id, false));
                } else if (type.equals("radio") || tag.equals("textarea") && text.contains("varför")) {
                    actions.add(skip(id, true));
                    questions.add(Map.of("fieldId", id, "question", f.get("question").asText().isBlank() ? f.get("label").asText() : f.get("question").asText()));
                } else if (f.get("type").asText().equals("text") && text.contains("meddelande")) {
                    actions.add(act(id, "fill", "Hej, jag är intresserad.", "none", "cv"));
                } else {
                    actions.add(skip(id, false));
                }
            }
            return MAPPER.writeValueAsString(Map.of("actions", actions, "questionsForUser", dedupe(questions)));
        } catch (Exception e) {
            throw new IllegalStateException("fake mapper could not read the prompt", e);
        }
    }

    /** The n-th fenced json block of the prompt. */
    private static String block(String prompt, int n) {
        int from = 0;
        for (int i = 0; i <= n; i++) {
            from = prompt.indexOf("```json\n", from) + 8;
            if (i < n) {
                from = prompt.indexOf("\n```", from);
            }
        }
        return prompt.substring(from, prompt.indexOf("\n```", from));
    }

    private static List<Map<String, String>> dedupe(List<Map<String, String>> q) {
        return q.stream().collect(java.util.stream.Collectors.toMap(m -> m.get("question"), m -> m, (a, b) -> a, java.util.LinkedHashMap::new))
                .values().stream().toList();
    }

    private static Map<String, Object> act(String id, String action, String value, String file, String source) {
        return Map.of("fieldId", id, "action", action, "value", value, "file", file, "reason", "fake", "needsUser", false, "source", source);
    }

    private static Map<String, Object> skip(String id, boolean needsUser) {
        return Map.of("fieldId", id, "action", "skip", "value", "", "file", "none", "reason", "cannot answer", "needsUser", needsUser, "source", "none");
    }
}
