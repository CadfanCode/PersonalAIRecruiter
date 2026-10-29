package app.jobpilot.apply;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import app.jobpilot.apply.FormActions.MappedAction;
import app.jobpilot.apply.FormActions.Question;
import app.jobpilot.cvdb.AnswerKeys;
import app.jobpilot.cvdb.CvModel.Profile;

/**
 * Decides which of Claude's proposed actions may run (SPEC 9.2 step 5, 9.3). The page and the model are both
 * untrusted: every action must name a field that was just extracted, fit that field's type, avoid consent and
 * credential fields, and carry a value that can be traced to the profile, the answers memory or an approved
 * document.
 */
public final class ActionValidator {

    public record Planned(FormField field, String action, String value, Path file, String source) {
    }

    public record Skipped(String fieldId, String label, String reason) {
    }

    public record Validated(List<Planned> planned, List<Skipped> skipped, List<Question> questions) {
    }

    static final int MAX_VALUE = 5_000;
    private static final Set<String> SOURCES = Set.of("profile", "memory", "cv", "document");
    private static final Set<String> NEVER = Set.of("password", "hidden", "submit", "button", "image", "reset");

    private static final Pattern EMAIL = pattern("e-?post|e-?mail|mail address");
    private static final Pattern PHONE = pattern("telefon|phone|mobil|tel\\b");
    private static final Pattern FIRST = pattern("förnamn|first ?name|given ?name");
    private static final Pattern LAST = pattern("efternamn|last ?name|surname|family ?name");
    private static final Pattern FULL = pattern("^\\s*(för- och efternamn|fullständigt namn|full name|namn|name|ditt namn|your name)\\s*[*:]?\\s*$");
    private static final Pattern CITY = pattern("\\b(ort|stad|city|town|bostadsort|postort)\\b");

    private static final Pattern NEGATIVE = pattern("(no|nej|inte|false|none|ingen)");

    private ActionValidator() {
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public static Validated validate(List<FormField> fields, FormActions mapped, ApplyContext ctx) {
        Map<String, FormField> byId = new LinkedHashMap<>();
        fields.forEach(f -> byId.put(f.id(), f));
        List<Planned> planned = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        Map<String, Question> questions = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();

        for (MappedAction a : mapped.actions() == null ? List.<MappedAction>of() : mapped.actions()) {
            FormField f = a.fieldId() == null ? null : byId.get(a.fieldId());
            if (f == null) {
                skipped.add(new Skipped(String.valueOf(a.fieldId()), "", "not a field on this page"));
                continue;
            }
            if (!seen.add(f.id())) {
                skipped.add(new Skipped(f.id(), label(f), "proposed twice; only the first is used"));
                continue;
            }
            String action = a.action() == null ? "" : a.action().toLowerCase(Locale.ROOT);
            String refusal = refusal(f);
            if (refusal != null) {
                skipped.add(new Skipped(f.id(), label(f), refusal));
                continue;
            }
            if (action.equals("skip") || a.needsUser()) {
                skipped.add(new Skipped(f.id(), label(f), nzOr(a.reason(), "needs your input")));
                if (a.needsUser()) {
                    addQuestion(questions, f, f.questionText());
                }
                continue;
            }
            String problem = switch (action) {
                case "fill" -> fill(f, a, ctx);
                case "select", "choose" -> select(f, a);
                case "check", "uncheck" -> choice(f, a, action.equals("check"), ctx);
                case "upload" -> upload(f, a, ctx);
                default -> "unknown action '" + a.action() + "'";
            };
            if (problem != null) {
                skipped.add(new Skipped(f.id(), label(f), problem));
                continue;
            }
            planned.add(new Planned(f, action, action.equals("upload") ? null : clean(a.value()),
                    action.equals("upload") ? ctx.files().get(a.file().toLowerCase(Locale.ROOT)) : null, a.source()));
        }

        for (Question q : mapped.questionsForUser() == null ? List.<Question>of() : mapped.questionsForUser()) {
            FormField f = q.fieldId() == null ? null : byId.get(q.fieldId());
            if (f != null && refusal(f) == null) {
                addQuestion(questions, f, q.question() == null || q.question().isBlank() ? f.questionText() : q.question());
            }
        }
        return new Validated(planned, skipped, new ArrayList<>(questions.values()));
    }

    /** One question per question text: the Ja and Nej buttons of a radio group are one question, not two. */
    private static void addQuestion(Map<String, Question> questions, FormField f, String text) {
        if (questions.values().stream().anyMatch(q -> q.fieldId().equals(f.id()))) {
            return; // the same field asked about twice, in different words
        }
        questions.putIfAbsent(AnswerKeys.normalise(f.isChoice() && !f.legend().isBlank() ? f.legend() : text), new Question(f.id(), text));
    }

    /** A personal-number field, judged by its own words and the text right above it. */
    static boolean isPersonalNumber(FormField f) {
        return GuardPatterns.PERSONAL_NUMBER.matcher(f.ownText()).find()
                || (f.nearbyText() != null && GuardPatterns.PERSONAL_NUMBER.matcher(f.nearbyText()).find());
    }

    // ---- per action ----

    private static String refusal(FormField f) {
        String type = f.type() == null ? "" : f.type().toLowerCase(Locale.ROOT);
        if (NEVER.contains(type)) {
            return "refused: " + type + " fields are never touched";
        }
        if (GuardPatterns.CREDENTIAL.matcher(f.ownText()).find()) {
            return "refused: looks like a login or BankID field";
        }
        if (isPersonalNumber(f)) {
            return "refused: personal identity numbers are left for you in the browser";
        }
        if (GuardPatterns.isConsent(f.consentText())) {
            return "left for you: consent, terms and privacy choices";
        }
        if (f.disabled()) {
            return "the field is disabled or read-only";
        }
        return null;
    }

    private static String fill(FormField f, MappedAction a, ApplyContext ctx) {
        if (!f.isText()) {
            return "fill needs a text field, this is " + f.type();
        }
        String value = a.value() == null ? "" : a.value().strip();
        if (value.isEmpty()) {
            return "no value to fill";
        }
        if (value.length() > MAX_VALUE) {
            return "the value is too long";
        }
        if (f.value() != null && !f.value().isBlank() && !f.value().equals(value)) {
            return "already has a value; left as it is";
        }
        String source = a.source() == null ? "" : a.source().toLowerCase(Locale.ROOT);
        if (!SOURCES.contains(source)) {
            return "no source given for the value";
        }
        if (expectedFor(f, ctx.profile()) != null) {
            return profileMismatch(f, value, ctx.profile()); // name, email, phone and city: exactly the profile, whatever the source
        }
        return source.equals("memory") ? memoryMismatch(f, value, ctx) : source.equals("profile") ? notFromProfile(f, value, ctx.profile()) : null;
    }

    private static String select(FormField f, MappedAction a) {
        if (!"select".equals(f.tag())) {
            return "select needs a drop-down, this is " + f.type();
        }
        String value = a.value() == null ? "" : a.value().strip();
        if (value.isEmpty() || value.length() > MAX_VALUE) {
            return "no option to choose";
        }
        // A native drop-down lists its options, so a made-up one is refused here; a custom one lists them only once opened, and
        // the guard then picks nothing that is not in the list.
        boolean known = f.options() == null || f.options().isEmpty() || f.options().stream().anyMatch(o ->
                o.value().equalsIgnoreCase(value) || o.label().equalsIgnoreCase(value)
                        || o.label().toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT)));
        return known ? sourceProblem(a) : "no such option: " + value;
    }

    private static String choice(FormField f, MappedAction a, boolean check, ApplyContext ctx) {
        if (!f.isChoice()) {
            return (check ? "check" : "uncheck") + " needs a checkbox or radio, this is " + f.type();
        }
        if (!check && "radio".equals(f.type())) {
            return "a radio button cannot be unticked";
        }
        if (check == f.checked()) {
            return check ? "already ticked" : "already unticked";
        }
        String problem = sourceProblem(a);
        if (problem != null) {
            return problem;
        }
        // A "No" is a claim too: it is only given from an answer the person gave before, never from a gap in the CV.
        if (!"memory".equalsIgnoreCase(a.source()) && NEGATIVE.matcher(AnswerKeys.normalise(nz(f.optionLabel()).isBlank() ? f.value() : f.optionLabel())).matches()) {
            return "a No is only given from an answer you gave before, not because the CV does not mention it";
        }
        if ("memory".equalsIgnoreCase(a.source())) {
            String answer = ctx.memory().get(AnswerKeys.normalise(f.questionText()));
            String option = AnswerKeys.normalise(f.optionLabel() == null || f.optionLabel().isBlank() ? f.value() : f.optionLabel());
            if (answer == null || option.isEmpty() || !AnswerKeys.normalise(answer).contains(option) && !option.contains(AnswerKeys.normalise(answer))) {
                return "not an answer you gave before";
            }
        }
        return null;
    }

    private static String upload(FormField f, MappedAction a, ApplyContext ctx) {
        if (!"file".equals(f.type())) {
            return "upload needs a file input, this is " + f.type();
        }
        String which = a.file() == null ? "" : a.file().toLowerCase(Locale.ROOT);
        if (!which.equals("cv") && !which.equals("letter")) {
            return "upload needs file cv or letter";
        }
        return ctx.files().containsKey(which) ? null : "your " + (which.equals("cv") ? "CV" : "cover letter") + " is not approved yet, so it is not uploaded";
    }

    private static String sourceProblem(MappedAction a) {
        String source = a.source() == null ? "" : a.source().toLowerCase(Locale.ROOT);
        return SOURCES.contains(source) ? null : "no source given for the choice";
    }

    // ---- values must be traceable ----

    /** Name, email, phone and city fields may only receive exactly what the profile says. */
    static String profileMismatch(FormField f, String value, Profile p) {
        Set<String> expected = expectedFor(f, p);
        if (expected == null) {
            return null;
        }
        return expected.contains(value.strip()) ? null : "does not match your profile for this field";
    }

    private static Set<String> expectedFor(FormField f, Profile p) {
        String text = String.join(" ", nz(f.label()), nz(f.ariaLabel()), nz(f.placeholder()), nz(f.name()));
        String type = nz(f.type()).toLowerCase(Locale.ROOT);
        String[] name = nz(p.fullName()).strip().split("\\s+", 2);
        if (type.equals("email") || EMAIL.matcher(text).find()) {
            return setOf(p.email());
        }
        if (type.equals("tel") || PHONE.matcher(text).find()) {
            return setOf(p.phone(), p.phoneInternational());
        }
        if (FIRST.matcher(text).find()) {
            return setOf(name[0]);
        }
        if (LAST.matcher(text).find()) {
            return setOf(name.length > 1 ? name[1] : null);
        }
        if (FULL.matcher(nz(f.label()).isBlank() ? nz(f.ariaLabel()) : f.label()).find()) {
            return setOf(p.fullName());
        }
        if (CITY.matcher(text).find()) {
            return setOf(p.city());
        }
        return null;
    }

    private static Set<String> setOf(String... values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v.strip());
            }
        }
        return out;
    }

    private static String notFromProfile(FormField f, String value, Profile p) {
        boolean known = Stream.of(p.fullName(), p.email(), p.phone(), p.phoneInternational(), p.city(), p.website(), p.github(),
                p.linkedin(), p.headline()).filter(s -> s != null).anyMatch(s -> s.strip().equals(value.strip()));
        return known ? null : "the value is not in your profile";
    }

    private static String memoryMismatch(FormField f, String value, ApplyContext ctx) {
        String answer = ctx.memory().get(AnswerKeys.normalise(f.questionText()));
        return answer != null && answer.strip().equals(value.strip()) ? null : "not an answer you gave before";
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
    }

    private static String label(FormField f) {
        String q = f.questionText();
        return q.length() > 80 ? q.substring(0, 80) : q;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String nzOr(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
