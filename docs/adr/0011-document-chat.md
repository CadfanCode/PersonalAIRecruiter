# 0011 Ask Claude: chat adjustments to documents

Date: 2026-10-27. Status: accepted.

The user can ask for fine adjustments to a generated CV or letter in their own words.

- **Same rules as generation.** The model may use any fact of any entry the user has not excluded for the job, and only those
  (rule 4). The answer is validated against the writeCv/writeLetter schema, a CV goes through `CvAssembler` (so titles, employers,
  places and dates stay database-owned), the validator runs with one automatic rewrite, and the entries a revision now uses join the
  job's selection so validation and a later regeneration agree with the document.
- **A change is an edit.** It saves a new version, revokes approval (only approved documents are uploaded) and the page says so.
  Undo saves the previous content back through the normal edit endpoint.
- **What is on screen is the base.** The page sends its current draft, including unsaved edits, plus the recent conversation.
- **Honest refusals.** A request that needs something the CV database does not contain changes nothing and says what is missing.
