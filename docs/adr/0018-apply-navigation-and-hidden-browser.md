# 0018 The apply pipeline finds the form itself; the browser stays out of sight until needed

Date: 2026-10-28. Status: accepted. Amends CLAUDE.md hard rule 1 and ADR 0005 ("never clicks Ansök").

- **Root cause of the false sign-in prompt (web103.reachmee.com).** Checked on the real ad: it has no sign-in text; the word
  "registerkontroll" in the ad matched the bare `register` pattern, there were 0 fields, so the page was called a wall. The
  Teamtailor ad showed the other trap: a "Log in" link in the header. Both pages have a way on (an apply link or button).
  The classifier now uses whole-word patterns, looks for an apply route before declaring a wall, counts BankID and sign-in words
  only on a near-empty page, and returns its reasons (logged, shown on the card).
- **Rule 1 amended.** The link or button that only opens the form may be followed. Never a submit, never a sending word, never on a
  page that already is a form, never after anything was filled, never inside a form with inputs. A link with an address is loaded
  (a GET, after the http(s) and paste-only checks), a button is clicked through `ClickPurpose.OPEN_APPLICATION_FORM`. The guard
  also now refuses a `<button>` without a type inside a `<form>` (a default submit) and treats `registrera|register|spara
  ansökan` as sending words (the real ReachMee final button reads "Registrera").
- **No ATS adapters from guesses.** ReachMee needed none (a plain link to `/apply`). Teamtailor ("Apply for this job" button that
  opens an inline form) and Jobylon ("I'm interested" link to `/applications/jobs/N/create/`) were read on real public ads and
  are covered by the generic labels; Varbi's ad has only "Log in", which is a genuine wall. Fixtures model each.
- **Hidden browser.** Playwright cannot show a headless window later, and a relaunch loses the form state. A headed window is
  minimised instead (DevTools `Browser.setWindowBounds`), with background throttling switched off, and restored for a sign-in,
  a form ready for review or a take-over. Positioning it off-screen was tried first and does not work here: under XWayland the
  window manager clamps it to the corner, so it stayed visible. Chromium is started through XWayland on a Wayland session. One window
  holds all tabs. Verified by `BackgroundBrowserTest` on a real display (starts minimised, a hidden page keeps its timers running,
  restores, minimises again).
- **Stages, timeouts, failure.** Six stages are tracked on the tab and shown as a progress bar. Opening the ad and the model call
  are tried twice; finding the form has a 90 s deadline and at most 4 pages; every browser call has a hard limit. A failure names the
  stage and the reason and offers "Try again" and "Open the browser to take over". A tab that waits for the user to open the form
  is polled and continues by itself.
- **Known gap.** Pressing "Nästa" or "Fortsätt" in a multi-step form is not automated: those labels also end many applications.
