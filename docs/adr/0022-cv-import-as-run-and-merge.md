# 0022 CV import is a background run, and imported entries are merged

Date: 2026-10-28. Status: accepted. Amends SPEC section 13 (new) and applies CLAUDE.md's rule that long work runs as pipeline runs.

- **Run.** Reading a CV takes minutes, and it was a synchronous request tied to the page. It is now a `pipeline_run` of kind `IMPORT`
  (migration V12). The preview is held in memory (one hour); a restart marks an unfinished reading FAILED like any other run. The UI
  polls `/api/cv/import/current`, so leaving the page does not matter and the top bar shows the reading while it goes on.
- **Merge.** An imported entry that matches a stored one is merged into it. `EntryMatcher` is stricter than before, because a wrong
  match now puts facts under the wrong job: a matching organisation also needs a similar title or the same start period, so a promotion
  at one employer stays a separate entry. Amending adds facts (`FactMatcher`, 80 % word overlap), fills blank fields and unions tags;
  it never edits or deletes existing facts, whose ids documents cite. The user can choose "add as a new entry" per entry.
- **Batch.** Entries from several uploaded files that describe the same job are folded into one before matching the database.
- **Fact comparison.** The word matcher is the cheap first pass. Whatever it cannot settle on a matched entry (rewordings, the other
  language, a stored fact with only a Swedish text) goes in one batched `matchFacts` call inside the reading, which returns the candidates
  that are already stated. The preview carries the decision per fact (`knownFacts`) and the commit honours it (`skipFacts`) instead of
  recomputing it. If the call fails, the word match stands and the reading still succeeds; a repeated fact costs the user a click.
