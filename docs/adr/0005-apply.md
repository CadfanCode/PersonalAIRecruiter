# 0005 How applying is kept safe

Date: 2026-10-27. Status: accepted.

CLAUDE.md hard rules 1 to 3 (never submit, never handle passwords or BankID, never tick consent) are
enforced in code, not only in prompts.

- **One choke point.** `BrowserGuard` is the only class that fills, selects, ticks, uploads or clicks on
  an external page. A click needs a named purpose (`COOKIE_NECESSARY`, `AUTOCOMPLETE_OPTION`; milestone 6 adds
  `AKTIVITET_SPARA`). The SPEC 9.3 deny regex applies on top, against the element's text, value, aria-label,
  title and enclosing button, and `type=submit` is refused outright. Enter is never pressed.
- **Proof.** `NoUnguardedBrowserCallsTest` scans `src/main/java` and fails if any other class calls a raw
  click, key press, `evaluate`, `dispatchEvent` or form submit, or imports an element-level Playwright type.
- **Fixed scripts only.** `evaluate` runs two read-only scripts from the classpath; nothing is built from
  model output or page text.
- **Distrust both sides.** The model's form answer is validated by `ActionValidator` (field exists,
  type fits, not password or consent, value traceable to profile, answers memory or an approved document). The
  guard re-checks the live element (type, id, consent text) before acting. After acting, the page is read again to
  prove the value stuck.
- **One thread.** Playwright for Java is not thread-safe, so `BrowserService` runs everything on one thread,
  starts Chromium lazily, and uses only `~/.jobpilot/browser-profile`. Model calls happen on the pipeline thread
  so a slow model never freezes the login poll.
- **The user acts at walls.** A visible password field, BankID or account-creation text, or an ad page whose only
  way in is an "Ansök" button puts the tab in a waiting state. The 3 s poll is read-only.
- **Tests never touch real sites.** Fixtures (Ponty, Workday, Varbi, Jobshark, cookie banners, an iframe form, a
  re-rendering form) carry trap buttons and a `window.__submitted` detector. Only http(s) addresses are opened
  (file: only when a test enables it).
