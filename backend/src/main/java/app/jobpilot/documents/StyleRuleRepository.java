package app.jobpilot.documents;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The editable writing rules (CLAUDE.md) and banned phrases (SPEC 8.4). */
@Repository
public class StyleRuleRepository {

    private final JdbcClient jdbc;

    public StyleRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Enabled rule texts verbatim, one bullet per rule, for the prompts that write documents (SPEC 5.2). */
    public String rulesText() {
        return jdbc.sql("SELECT rule_text FROM style_rule WHERE enabled ORDER BY id").query(String.class).list()
                .stream().map(t -> "- " + t).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    public record Rule(long id, String ruleKey, String ruleText, boolean enabled) {
    }

    public record Phrase(long id, String language, String phrase, boolean enabled) {
    }

    public List<Rule> rules() {
        return jdbc.sql("SELECT id, rule_key, rule_text, enabled FROM style_rule ORDER BY id")
                .query((rs, n) -> new Rule(rs.getLong("id"), rs.getString("rule_key"), rs.getString("rule_text"), rs.getBoolean("enabled"))).list();
    }

    public boolean updateRule(long id, String text, boolean enabled) {
        return jdbc.sql("UPDATE style_rule SET rule_text = :t, enabled = :e WHERE id = :id").param("t", text).param("e", enabled)
                .param("id", id).update() > 0;
    }

    public List<Phrase> phrases() {
        return jdbc.sql("SELECT id, language, phrase, enabled FROM banned_phrase ORDER BY language, phrase")
                .query((rs, n) -> new Phrase(rs.getLong("id"), rs.getString("language"), rs.getString("phrase"), rs.getBoolean("enabled"))).list();
    }

    public Phrase addPhrase(String language, String phrase) {
        return jdbc.sql("""
                INSERT INTO banned_phrase (language, phrase) VALUES (:l, :p)
                ON CONFLICT (language, phrase) DO UPDATE SET enabled = TRUE
                RETURNING id, language, phrase, enabled""").param("l", language).param("p", phrase)
                .query((rs, n) -> new Phrase(rs.getLong("id"), rs.getString("language"), rs.getString("phrase"), rs.getBoolean("enabled"))).single();
    }

    public boolean setPhraseEnabled(long id, boolean enabled) {
        return jdbc.sql("UPDATE banned_phrase SET enabled = :e WHERE id = :id").param("e", enabled).param("id", id).update() > 0;
    }

    public boolean deletePhrase(long id) {
        return jdbc.sql("DELETE FROM banned_phrase WHERE id = :id").param("id", id).update() > 0;
    }

    public List<String> bannedPhrases(String language) {
        return jdbc.sql("SELECT phrase FROM banned_phrase WHERE enabled AND language = :l ORDER BY id")
                .param("l", language).query(String.class).list();
    }
}
