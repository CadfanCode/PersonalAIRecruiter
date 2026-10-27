# 0006 Writing to the aktivitetsrapport safely

Date: 2026-10-27. Status: accepted.

Saving an activity writes to a government record, so the automation is built to fail safe rather than to be
convenient.

- **Verify, then save.** All seven values are filled, the form is read again, and every value must be on the page
  exactly as intended before Spara is clicked. Any doubt means that job is not saved.
- **One click, never a retry.** Spara is clicked once per job. If the confirmation toast does not appear the
  result is UNCONFIRMED and the whole batch stops: a retry could create a duplicate, and only the user can check.
- **A narrow click purpose.** `AKTIVITET_SPARA` needs the exact text "Spara", the exact configured form address
  and the purpose itself, and `type=submit` is allowed only for it. "Skicka in" is refused for every purpose.
  Each condition has its own test. The scan test pins the list of purposes, so adding one is a visible change.
- **ArrowDown, never Enter.** SPEC 10 opens the ort list with ArrowDown. The guard contains exactly one key press
  and a test fails if any other key, or Enter, appears.
- **Confirm before writing.** The UI lists all seven values per job and the server writes what was confirmed, so a
  wrong employer label or date is caught by a person, not by a government clerk.
- **No model.** The form is fixed (SPEC 10), so the fields are mapped in Java by label. Nothing the page says is
  ever interpreted by Claude here.
- **Duplicates.** The saved list is read with a fixed read-only script. If it cannot be read, nothing is saved,
  because duplicates cannot then be ruled out. A job already listed (same employer and date) is skipped but stays
  APPLIED, since it may be a second application there. The saved check is repeated right before the click, only
  one aktivitet run can be active at a time (409), and any error after the click is recorded as UNCONFIRMED.
- **Unverified.** The real page's markup is not available from here. The guard accepts the two list shapes SPEC
  10 and common widgets use, requires an exact match, and fails safe. Real-site acceptance is the user's.
