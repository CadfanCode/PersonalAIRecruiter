package se.caibirch.jobpilot.documents;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the validators need to judge a document against the CV database and the ad.
 *
 * @param allFacts every fact in the database by id (to tell "unknown" from "not selected")
 * @param selectedEntryIds entries chosen for this document; citing a fact of any other entry fails
 * @param skillNames names in the skill table, for the skills-line check
 * @param foreignEmployers employers named in the example letters that are not the target employer
 */
public record ValidationContext(String jobLanguage, boolean adAsksForNewGraduates, Map<Long, Fact> allFacts,
                                Set<Long> selectedEntryIds, Set<String> skillNames, List<String> bannedPhrases,
                                Set<String> foreignEmployers) {

    public record Fact(long id, long entryId, String textEn, String textSv) {

        public String text() {
            return (textEn == null ? "" : textEn) + " " + (textSv == null ? "" : textSv);
        }
    }
}
