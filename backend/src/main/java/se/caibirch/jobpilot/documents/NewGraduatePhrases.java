package se.caibirch.jobpilot.documents;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phrases that call someone a new graduate (SPEC 8.3). One list serves two purposes: the style
 * validator forbids them in documents unless the ad asks for new graduates, and the same list
 * detects that an ad does ask (the scorer's flag alone missed "i början av din karriär").
 */
public final class NewGraduatePhrases {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final List<Pattern> PATTERNS = List.of(
            // Swedish
            word("nyexaminerad\\w*"), word("nyutexaminerad\\w*"), word("nyligen examinerad\\w*"),
            word("nyligen tagit examen"), word("nyligen avslutat"),
            word("i början av (?:min|din|er|sin|vår|ens) (?:yrkes)?karriär\\w*"),
            word("i början av karriären"),
            // English
            word("newly qualified"), word("new graduates?"), word("recent graduates?"), word("recently graduated"),
            word("recently finished"), word("fresh graduates?"), word("early in (?:my|your|their|his|her) career"),
            word("early[- ]career"));

    private NewGraduatePhrases() {
    }

    private static Pattern word(String regex) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + regex + "(?![\\p{L}\\p{N}])", FLAGS);
    }

    /** The first matching phrase as written in the text, if any. */
    public static Optional<String> find(String text) {
        if (text == null) {
            return Optional.empty();
        }
        for (Pattern p : PATTERNS) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                return Optional.of(m.group());
            }
        }
        return Optional.empty();
    }

    public static boolean appearsIn(String text) {
        return find(text).isPresent();
    }
}
