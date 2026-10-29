package app.jobpilot.apply;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.jobpilot.apply.PageSnapshot.Node;
import app.jobpilot.claude.ClaudeTask;

/**
 * A deterministic stand-in for the model behind {@code browseApplication}. It reads the snapshot out of the prompt, as the real
 * model does, and answers by label, including the mistakes a real model could make (filling a password, ticking consent, pressing
 * a button that sends), so the tests prove the validator and the guard stop them.
 */
final class FakeBrowseAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern APPLY = Pattern.compile("^(ansök|apply|jag är intresserad|i['’]?m interested|apply for this job).*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** What a careless model presses: next words, but also the words that send. The guard has to tell them apart. */
    private static final Pattern PRESS = Pattern.compile("^(next|nästa|continue|fortsätt|save and continue|gå vidare|ansök|registrera|skicka.*|submit|send.*)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private FakeBrowseAgent() {
    }

    static String answer(ClaudeTask<?> task) {
        try {
            String prompt = task.prompt();
            int open = prompt.indexOf("```\n") + 4;
            PageSnapshot snapshot = PageSnapshot.parse(prompt.substring(open, prompt.indexOf("\n```", open)));
            JsonNode page = MAPPER.readTree(jsonBlock(prompt, 0));
            Map<String, String> memory = new java.util.HashMap<>();
            for (JsonNode m : MAPPER.readTree(jsonBlock(prompt, 4))) {
                memory.put(app.jobpilot.cvdb.AnswerKeys.normalise(m.get("question").asText()), m.get("answer").asText());
            }
            List<Map<String, Object>> actions = new ArrayList<>();
            List<Map<String, String>> questions = new ArrayList<>();

            for (FormField f : snapshot.fields()) {
                field(f, memory, actions, questions);
            }
            for (JsonNode file : page.get("fileInputs")) {
                String label = file.get("label").asText().toLowerCase(Locale.ROOT);
                boolean letter = label.contains("övriga") || label.contains("brev") || label.contains("letter");
                actions.add(act(file.get("id").asText(), "upload", "", letter ? "letter" : "cv", "document"));
            }

            boolean hasFields = !snapshot.fields().isEmpty() || page.get("fileInputs").size() > 0;
            String kind = hasFields ? "form" : "ad";
            if (snapshot.nodes().stream().anyMatch(n -> n.role().equals("heading") && n.name().toLowerCase(Locale.ROOT).matches(".*(granska|review).*"))) {
                kind = "review";
            }
            boolean password = page.get("visiblePasswordField").asBoolean();
            final boolean fields = hasFields;
            if (password || (!fields && snapshot.clickables().stream().noneMatch(c -> APPLY.matcher(c.name().strip()).matches())
                    && snapshot.clickables().stream().anyMatch(c -> c.name().toLowerCase(Locale.ROOT).matches(".*(log in|logga in|sign in).*")))) {
                kind = "login";
            }
            if (!kind.equals("review")) {
                snapshot.clickables().stream().filter(c -> hasFields ? PRESS.matcher(c.name().strip()).matches() : APPLY.matcher(c.name().strip()).matches())
                        .findFirst().ifPresent(c -> actions.add(act(c.ref(), "click", "", "none", "none")));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pageKind", kind);
            out.put("summary", "fake: " + kind);
            out.put("actions", actions);
            out.put("questionsForUser", dedupe(questions));
            return MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            throw new IllegalStateException("fake agent could not read the prompt", e);
        }
    }

    private static void field(FormField f, Map<String, String> memory, List<Map<String, Object>> actions, List<Map<String, String>> questions) {
        String id = f.id();
        String label = f.label() == null ? "" : f.label();
        String text = (label + " " + f.legend() + " " + f.optionLabel()).toLowerCase(Locale.ROOT);
        if (f.isChoice()) {
            if (f.type().equals("radio") && text.contains("rätt att arbeta")) {
                actions.add(act(id, "check", f.optionLabel(), "none", "memory"));
            } else if (f.type().equals("radio") && memory.containsKey(app.jobpilot.cvdb.AnswerKeys.normalise(f.legend()))) {
                String answer = memory.get(app.jobpilot.cvdb.AnswerKeys.normalise(f.legend()));
                actions.add(answer.equalsIgnoreCase(f.optionLabel()) ? act(id, "check", answer, "none", "memory") : skip(id, false));
            } else if (f.type().equals("radio")) {
                actions.add(skip(id, true));
                questions.add(Map.of("fieldId", id, "question", f.legend().isBlank() ? label : f.legend()));
            } else {
                actions.add(act(id, "check", "", "none", "cv")); // a mistake: ticking consent
            }
        } else if (f.tag().equals("select")) {
            if (text.contains("kategori") || text.contains("category")) {
                actions.add(act(id, "choose", "CV", "none", "document"));
            } else if (text.contains("country") || text.contains("land")) {
                actions.add(act(id, "choose", "Sweden", "none", "profile"));
            } else {
                actions.add(skip(id, false));
            }
        } else if (text.contains("password") || text.contains("lösenord")) {
            actions.add(act(id, "fill", "hunter2", "none", "profile")); // a mistake the validator must stop
        } else if (text.contains("e-post") || text.contains("email")) {
            actions.add(act(id, "fill", "caibirch@proton.me", "none", "profile"));
        } else if (text.contains("telefon") || text.contains("phone")) {
            actions.add(act(id, "fill", "076-185 95 27", "none", "profile"));
        } else if (text.contains("förnamn") || text.contains("first name")) {
            actions.add(act(id, "fill", "Cai", "none", "profile"));
        } else if (text.contains("efternamn") || text.contains("last name")) {
            actions.add(act(id, "fill", "Birch", "none", "profile"));
        } else if (label.trim().equalsIgnoreCase("namn") || label.trim().equalsIgnoreCase("name")) {
            actions.add(act(id, "fill", "Cai Birch", "none", "profile"));
        } else if (text.contains("länk") || text.contains("url") || text.contains("website")) {
            actions.add(act(id, "fill", "https://www.caibirch.com", "none", "profile"));
        } else if (text.contains("meddelande")) {
            actions.add(act(id, "fill", "Hej, jag är intresserad.", "none", "cv"));
        } else if (text.contains("varför")) {
            actions.add(skip(id, true));
            questions.add(Map.of("fieldId", id, "question", label));
        } else {
            actions.add(skip(id, false));
        }
    }

    /** The n-th fenced json block of the prompt. */
    private static String jsonBlock(String prompt, int n) {
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
        return q.stream().collect(java.util.stream.Collectors.toMap(m -> m.get("question"), m -> m, (a, b) -> a, LinkedHashMap::new))
                .values().stream().toList();
    }

    private static Map<String, Object> act(String id, String action, String value, String file, String source) {
        return Map.of("fieldId", id, "action", action, "value", value, "file", file, "reason", "fake", "needsUser", false, "source", source);
    }

    private static Map<String, Object> skip(String id, boolean needsUser) {
        return Map.of("fieldId", id, "action", "skip", "value", "", "file", "none", "reason", "cannot answer", "needsUser", needsUser, "source", "none");
    }
}
