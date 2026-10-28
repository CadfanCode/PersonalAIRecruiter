# 0015 The pipeline as five stages with approval gating, snapshots and a time-left estimate

Date: 2026-10-28. Status: accepted. Builds on ADR 0008, 0009, 0010 and 0011.

- **Five stages.** Search mode, Run search, Documents, Apply, Submit. The old "Finalize Applications" page is split: Apply (fills
  the forms) and Submit (the user's own click and confirmation). `/finalize` redirects to `/apply`.
- **Approval gates Apply.** The server decides: `POST /api/applications/apply` opens a tab only for applications with both
  documents approved and returns the others as `skipped` with the reason; each application also carries `readyToApply` and
  `blockers`. "Approve all" never fails as a whole: it returns, per document, approved / already approved / refused with the
  validator's reason (a document with FAIL findings still cannot be approved).
- **A snapshot of what was submitted.** Confirming a submission copies the ad text and the CV and letter content and PDF bytes into
  `submitted_application`. Editing, regenerating, chatting or undoing a document afterwards never changes it. Undo removes the
  snapshot (the user says they did not send it) unless the application was already reported.
- **Reported status** is read from the aktivitet log, which already refuses a job that was saved once; it is shown as a pill and
  the Activity report lists unreported applications first.
- **Live progress.** Ads are linked to the run before they are scored, so the table fills during the run. The time left is
  `elapsed in the step * (1 - done) / done` while scoring (progress 45 to 95) and absent otherwise (`etaSeconds`).
- **Prompt mode.** Max age is read from the prompt; ads to score default to 20 and are capped at 150 in every mode; the profile
  review is always a step; Platsbanken is always searched and career pages are the `search.careerPages` setting (the run request
  may still set `includeWeb` for tests and API users). Discovery builds and accepts the profile itself.
- **Onboarding reads the CV after Claude is connected**, because reading needs Claude. The files are held in the browser through
  step 1 and sent right after the step 2 test passes. The import also reads the person's contact details and fills only blank
  profile fields.
- **Notifications** use the Web Notifications API from the app window (one permission prompt), plus an in-app message.
