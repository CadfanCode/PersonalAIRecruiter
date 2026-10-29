# 0025 What persists: submitted work and the user's chosen work, nothing else

Date: 2026-10-29. Status: accepted.

- **Problem.** Search runs, the ads they found and applications the user deleted stayed in the database for ever.
- **Decision.** Three kinds of data, three lifetimes (`RetentionService`).
  1. *Search data* (finished and waiting search runs, the ads only they found, search profiles nothing refers to) is scratch: it is
     cleared when the program starts, when it closes (`ContextClosedEvent`) and when a new Discovery or Prompt search starts.
     Queued and running runs are never touched. Adding a job by its link does not clear anything.
  2. *Chosen work* (an application the user selected, with its documents) stays until the user deletes it or submits it. The run it
     was chosen from also stays, holding only that application's ad, because the application's score is read through that run.
  3. *Submitted work* (the application, its frozen snapshot in Submitted applications, the aktivitetsrapport log) is the user's
     record and is never purged or deleted by JobPilot.
- **Delete.** Documents has a Delete button next to Approve all; the checkbox of every row (not only ready ones) selects. After
  a confirmation dialog `POST /api/applications/delete {jobIds}` closes the apply tab, deletes the job row (which cascades to
  documents, scores, run links, form snapshots and documents runs) and the PDF files on disk. Nothing is left in any table.
  A submitted application, or one whose documents are still being written, refuses the whole request with 409 and deletes nothing.
- **Consequence.** Ads that were only on screen in Results are gone after a restart; the user searches again. Deleting an ad's
  application means a later search can show the same ad as new.
- **Also in this change.** A Platsbanken link (`arbetsformedlingen.se/platsbanken/annonser/{id}`) is read from the JobTech
  `/ad/{id}` API, not by fetching the page: the page is drawn by a script, so fetching returned only menu and cookie text, which
  passed the length check and failed as "not a job ad". Any fetched page that Claude says is not an ad now waits for pasted text
  instead of failing.
