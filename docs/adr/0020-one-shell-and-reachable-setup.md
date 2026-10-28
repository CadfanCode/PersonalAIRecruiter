# 0020 One shell around everything, setup that is not revisited, and a menu that never disrupts work

Date: 2026-10-28. Status: accepted. Amends the navigation described in SPEC 2.2 and 12 and ADR 0015.

- **Problem.** The welcome steps had their own layout without the bottom menu, `RequireSetup` redirected every route to `/welcome` while
  setup was open, the whole page tree was remounted on each navigation, and the CV files held for setup lived in the page's own state.
  Going anywhere lost work, and finished setup could be walked back into.
- **One shell.** `Layout` (top bar, page, bottom menu) wraps every route including `/welcome`. Only the search pipeline routes (`/`,
  `/runs`, `/documents`, `/apply`, `/submit`) require finished setup; the CV database, Settings, Submitted, Extras and Aktivitet always open.
- **State outside the screen.** `SetupProvider` (above the routes) holds the setup step, the held CV files and the CV reading. `useDraft`
  (an in-memory map keyed per form) holds the Mode forms, the open document's draft and chat, the CV import and the activity-report run;
  long-running calls are awaited with promises, not per-call `onSuccess` options, so they still finish when the screen is gone.
  Searches, document runs and applications already run on the server and are only polled.
- **Progress bar as navigation.** During setup the bar shows the four setup steps (buttons); afterwards the five pipeline stages (links).
  A step or stage whose work has not been done is not blocked: it opens a "has not been started yet, please complete X first" page.
  Jumping ahead from the bar never counts as reaching a step; only Continue does.
- **Setup is not revisited.** `/welcome` redirects to Mode once setup is complete. The "Run setup again" panel is gone; changing the CV,
  country or Claude connection is done from the menu (CV database, `/settings/country`, `/settings/connection`). A completed install whose
  Claude connection no longer passes (for example one that used Gemini, ADR 0019) is reported by `GET /api/onboarding` as not complete
  at the Claude step, so it is sent to that one step.
- **Theme.** Light by default, never from the operating system; a switch in the top right corner; only "dark" is stored. Fonts are bundled
  with `@fontsource` so the installed app needs no network.
