package app.jobpilot.documents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import app.jobpilot.documents.DocumentModel.*;

/**
 * Runs {@link DocumentStyleValidator} and the fact-citation checks (SPEC 8.3). FAIL findings block
 * approval; WARN findings trigger the one automatic rewrite and are highlighted but never block.
 */
@Component
public class DocumentValidator {

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d\\s.,]*\\d|\\d");

    public Report validateCv(CvContent cv, ValidationContext ctx) {
        List<Violation> out = new ArrayList<>();
        Map<String, String> strings = new LinkedHashMap<>();
        strings.put("headline", cv.headline());
        for (int i = 0; i < cv.profileParagraphs().size(); i++) {
            strings.put("profileParagraphs[" + i + "]", cv.profileParagraphs().get(i));
        }
        for (int s = 0; s < cv.sections().size(); s++) {
            Section section = cv.sections().get(s);
            String sp = "sections[" + s + "]";
            strings.put(sp + ".heading", section.heading());
            for (int i = 0; i < section.items().size(); i++) {
                Item item = section.items().get(i);
                String ip = sp + ".items[" + i + "]";
                strings.put(ip + ".title", item.title());
                strings.put(ip + ".meta", item.meta());
                strings.put(ip + ".dateLabel", item.dateLabel());
                if (item.entryId() > 0 && !ctx.selectedEntryIds().contains(item.entryId())) {
                    out.add(new Violation("unselected_entry", Severity.FAIL, ip, String.valueOf(item.entryId()),
                            "The item refers to an entry that was not selected for this document"));
                }
                if ("SKILLS".equals(section.kind())) {
                    skillsLine(item, ip, ctx, out);
                }
                for (int b = 0; b < item.bullets().size(); b++) {
                    Bullet bullet = item.bullets().get(b);
                    String bp = ip + ".bullets[" + b + "]";
                    strings.put(bp, bullet.text());
                    citations(bullet.text(), bullet.factIds(), item.entryId(), bp, ctx, out);
                }
            }
        }
        String selectedText = ctx.allFacts().values().stream().filter(f -> ctx.selectedEntryIds().contains(f.entryId()))
                .map(ValidationContext.Fact::text).reduce("", (a, b) -> a + " " + b);
        for (int i = 0; i < cv.profileParagraphs().size(); i++) {
            numbers(cv.profileParagraphs().get(i), selectedText, "profileParagraphs[" + i + "]", out);
        }
        strings.put("languagesLine", cv.languagesLine());
        strings.put("gapNote", cv.gapNote());
        if (!ctx.jobLanguage().equals(cv.language())) {
            out.add(wrongLanguage(cv.language(), ctx));
        }
        out.addAll(style(ctx).validate(strings, false));
        return new Report(out, 0, 2);
    }

    public Report validateLetter(LetterContent letter, ValidationContext ctx) {
        List<Violation> out = new ArrayList<>();
        Map<String, String> strings = new LinkedHashMap<>();
        strings.put("subject", letter.subject());
        strings.put("subline", letter.subline());
        strings.put("greeting", letter.greeting());
        for (int i = 0; i < letter.paragraphs().size(); i++) {
            strings.put("paragraphs[" + i + "]", letter.paragraphs().get(i));
        }
        strings.put("closing", letter.closing());
        strings.put("signature", letter.signature());

        if (letter.factIdsUsed() == null || letter.factIdsUsed().isEmpty()) {
            out.add(new Violation("no_citation", Severity.FAIL, "factIdsUsed", "", "The letter cites no CV facts"));
        } else {
            for (long id : letter.factIdsUsed()) {
                checkFact(id, -1, "factIdsUsed", ctx, out);
            }
        }
        StringBuilder cited = new StringBuilder();
        if (letter.factIdsUsed() != null) {
            letter.factIdsUsed().forEach(id -> {
                var f = ctx.allFacts().get(id);
                if (f != null) {
                    cited.append(f.text()).append(' ');
                }
            });
        }
        for (int i = 0; i < letter.paragraphs().size(); i++) {
            numbers(letter.paragraphs().get(i), cited.toString(), "paragraphs[" + i + "]", out);
        }
        if (!ctx.jobLanguage().equals(letter.language())) {
            out.add(wrongLanguage(letter.language(), ctx));
        }
        out.addAll(style(ctx).validate(strings, true));
        return new Report(out, 0, 1);
    }

    private DocumentStyleValidator style(ValidationContext ctx) {
        return new DocumentStyleValidator(ctx.adAsksForNewGraduates(), ctx.bannedPhrases(), ctx.foreignEmployers());
    }

    private static Violation wrongLanguage(String actual, ValidationContext ctx) {
        return new Violation("wrong_language", Severity.FAIL, "language", String.valueOf(actual),
                "The ad is in '" + ctx.jobLanguage() + "' so the document must be too");
    }

    private void citations(String text, List<Long> factIds, long itemEntryId, String where, ValidationContext ctx,
                           List<Violation> out) {
        if (factIds == null || factIds.isEmpty()) {
            out.add(new Violation("no_citation", Severity.FAIL, where, text, "Every bullet must cite at least one CV fact"));
            return;
        }
        StringBuilder cited = new StringBuilder();
        for (long id : factIds) {
            checkFact(id, itemEntryId, where, ctx, out);
            var f = ctx.allFacts().get(id);
            if (f != null) {
                cited.append(f.text()).append(' ');
            }
        }
        numbers(text, cited.toString(), where, out);
    }

    private void checkFact(long id, long itemEntryId, String where, ValidationContext ctx, List<Violation> out) {
        var fact = ctx.allFacts().get(id);
        if (fact == null) {
            out.add(new Violation("unknown_fact", Severity.FAIL, where, String.valueOf(id), "Fact " + id + " does not exist"));
        } else if (!ctx.selectedEntryIds().contains(fact.entryId())) {
            out.add(new Violation("unselected_entry", Severity.FAIL, where, String.valueOf(id),
                    "Fact " + id + " belongs to an entry that was not selected for this document"));
        } else if (itemEntryId > 0 && fact.entryId() != itemEntryId) {
            out.add(new Violation("fact_from_other_entry", Severity.WARN, where, String.valueOf(id),
                    "Fact " + id + " belongs to a different entry than the item it appears under"));
        }
    }

    /** A number in the text that none of the cited facts contains may be an invented figure. */
    private void numbers(String text, String citedFactText, String where, List<Violation> out) {
        String haystack = digits(citedFactText);
        Matcher m = NUMBER.matcher(text == null ? "" : text);
        while (m.find()) {
            String n = digits(m.group());
            if (n.length() >= 2 && !haystack.contains(n)) {
                out.add(new Violation("number_not_in_facts", Severity.WARN, where, m.group().trim(),
                        "The number " + m.group().trim() + " does not appear in the cited facts"));
            }
        }
    }

    private void skillsLine(Item item, String where, ValidationContext ctx, List<Violation> out) {
        if (item.meta() == null) {
            return;
        }
        String factText = ctx.allFacts().values().stream().filter(f -> ctx.selectedEntryIds().contains(f.entryId()))
                .map(ValidationContext.Fact::text).reduce("", (a, b) -> a + " " + b).toLowerCase(Locale.ROOT);
        // Parentheses are stripped before splitting so "OAuth 2.0/OIDC (Keycloak, Auth0)" stays one skill.
        for (String token : item.meta().replaceAll("\\(.*?\\)", "").split("[,;]")) {
            String skill = token.trim().toLowerCase(Locale.ROOT);
            if (skill.length() < 2 || skill.split("\\s+").length > 4) {
                continue; // a descriptive phrase rather than a tool name
            }
            boolean known = ctx.skillNames().stream().map(s -> s.toLowerCase(Locale.ROOT))
                    .anyMatch(s -> s.contains(skill) || skill.contains(s)) || factText.contains(skill);
            if (!known) {
                out.add(new Violation("unknown_skill", Severity.WARN, where + ".meta", token.trim(),
                        "\"" + token.trim() + "\" is neither in the skill list nor in the selected facts"));
            }
        }
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }
}
