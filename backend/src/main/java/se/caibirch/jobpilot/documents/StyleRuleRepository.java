package se.caibirch.jobpilot.documents;

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

    public List<String> bannedPhrases(String language) {
        return jdbc.sql("SELECT phrase FROM banned_phrase WHERE enabled AND language = :l ORDER BY id")
                .param("l", language).query(String.class).list();
    }
}
