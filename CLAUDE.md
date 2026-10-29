# JobPilot

Installable desktop app (one user per install, everything stays on their machine) that runs a
job-application pipeline for any profession:
onboarding (CV, connect Claude, country, search mode) -> job search -> ranked results -> tailored
CV and cover letter (PDF), approved by the user -> browser form filling (stops before submit) ->
submitted applications -> logging in Arbetsförmedlingen's aktivitetsrapport.
Nothing here is specific to tech jobs: prompts, examples, scoring and UI work for nurses, teachers,
electricians and developers alike.

The full specification is in `docs/SPEC.md`. Read it before starting any milestone, and keep it
up to date when a decision changes.

## Stack

- Backend: Java 21, Spring Boot 3.x (latest stable), Spring Web, Spring Data JDBC, Flyway, PostgreSQL 16
  (embedded in the installed app, ADR 0012)
- Frontend: React 19, TypeScript, Vite, Tailwind CSS, TanStack Query, TanStack Table
- Browser automation: Playwright for Java, headed Chromium with a persistent profile
- PDF: openhtmltopdf (HTML + CSS -> PDF)
- AI: behind the `ClaudeRunner` interface. The only provider is the user's own Claude Code CLI called headless
  (`claude -p`, model chosen in Settings as an alias), under their Claude subscription (ADR 0019).
- Tests: JUnit 5, Mockito, Testcontainers (PostgreSQL), Vitest, React Testing Library

## Layout

```
backend/    Spring Boot app (Maven wrapper)
frontend/   Vite + React app
docs/       SPEC.md, ADRs (docs/adr/NNNN-title.md)
seed/       cv_database_seed.json (the user's CV facts)
packaging/  jpackage installer script, icons (docker-compose.yml is optional, for development)
```

## Commands

- `cd backend && ./mvnw spring-boot:run` starts the API on http://localhost:8080 with the embedded database in `~/.jobpilot/db`
  (`JOBPILOT_DATA_DIR` moves it; `JOBPILOT_DB_EMBEDDED=false` plus `docker compose up -d` uses a Docker PostgreSQL instead, with
  `JOBPILOT_DB_USER=jobpilot JOBPILOT_DB_PASSWORD=jobpilot` set, since no credentials are shipped in the app)
- `packaging/build-installer.sh [app-image]` builds the desktop installer for the current OS
- `cd backend && ./mvnw verify` runs all backend tests
- `cd frontend && npm run dev` starts the UI on http://localhost:5173 (proxies /api to 8080)
- `cd frontend && npm test` and `npm run build`
- `cd backend && ./mvnw exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"` installs the browser once

## Hard rules (never break these)

1. Never click a submit, send, register or apply-to-send button in any external site. The user submits by hand.
   The one exception is navigation (ADR 0018): the "Ansök" / "Apply" link or button of an ad page that only opens the
   application form may be followed, by `BrowserGuard` alone, and never on a page that already is a form, after JobPilot
   filled anything, for a submit button, inside a form with inputs, or for a label with a sending word.
   The other exception is moving a multi-step form on (ADR 0023): a button whose whole label is Next, Continue, Nästa,
   Fortsätt, Gå vidare, Apply manually or Continue as guest may be pressed, by `BrowserGuard` alone, never on a page
   whose visible heading reads review, summary, confirm or granska, never next to another visible control that submits,
   sends, finishes or registers, and never for a label with a sending word.
   Enforce this in code (see SPEC section 9.3), not only in prompts.
2. Never create accounts and never handle BankID or any Arbetsförmedlingen login. When a login,
   sign-up or BankID screen appears, pause and show "Waiting for you to sign in" in the UI.
   Passwords: JobPilot types a password only into the login of an employer portal for which the
   user explicitly chose "save my login" (stored only in the OS credential store, scoped per portal,
   viewable and deletable in Settings). It never guesses, reads back or logs a password, and never
   types one anywhere else.
3. Never tick consent, GDPR, terms or "join talent pool" boxes. Leave them for the user.
4. CVs and letters may only contain facts that exist in the CV database. Claude selects and
   rephrases; it never invents employers, dates, tools, degrees or results.
5. The aktivitetsrapport automation only saves activities ("Spara"). It never submits the
   monthly report.
6. The AI is only reached through `ClaudeRunner`, and its only provider is the user's own local Claude Code
   CLI under their Claude subscription (ADR 0019). No API keys of any kind are handled for it. Portal passwords
   live in the OS credential store only, never in plain text, the database or logs. No Claude.ai login flow,
   no key shipped with the app.

## Writing rules for generated CVs and letters

These are enforced by `DocumentStyleValidator` after every generation, and violations trigger
one automatic rewrite before failing:

- No em-dashes or en-dashes (U+2014, U+2013). Use commas, colons or full stops.
- Do not say the candidate is newly qualified, a new graduate, "nyexaminerad", "recently
  graduated" or "i början av karriären" unless the job ad explicitly asks for new graduates
  (`job.asksForNewGraduates == true`).
- Avoid typical AI phrasing (banned list in SPEC 8.4). Natural, specific, plain language.
- Write in the ad's language: Swedish ad -> Swedish documents, English ad -> English.
- Cover letter shape: friendly, humble but professional opening (who the candidate is by expertise,
  why this role, what qualifies them; never "My name is" / "Jag heter"), one concrete example,
  relevant experience with any real gaps stated honestly, team and personal paragraph,
  short thank-you, sign-off.
- CV profile opens with specialisation and evidence, not with the degree or its date.

## Conventions

- Package root `app.jobpilot`. Feature packages: `profile`, `cvdb`, `search`, `jobs`,
  `ranking`, `documents`, `apply`, `applications`, `aktivitet`, `claude`, `pipeline`, `discovery`, `manual`,
  `settings`, `desktop`, `country`, `credentials`, `onboarding`.
- Constructor injection, records for DTOs, no Lombok.
- Every Claude call has a JSON Schema in `backend/src/main/resources/claude/schemas/` and a
  prompt template in `backend/src/main/resources/claude/prompts/`. Never parse free text.
- Long-running work runs as `PipelineRun` steps on a bounded executor; the UI polls
  `/api/runs/{id}`. The one exception is the document chat (ADR 0011), which answers synchronously.
- Model calls share one adjustable limit (`ClaudeConcurrency`, default 4); never start your own
  unbounded fan-out.
- Record architecture decisions as ADRs in `docs/adr/`.
- Keep the UI in English with Swedish domain terms where they are proper names
  (Platsbanken, aktivitetsrapport, yrkesroll).
- Tests: unit tests mock `ClaudeRunner`; never call the real CLI in automated tests.
  Integration tests use Testcontainers (`jobpilot.db.embedded=false` in the test JVMs). Playwright tests run against local HTML fixtures in
  `backend/src/test/resources/fixtures/`, never against real sites.

## Model usage
- Sonnet is the main model; Opus is configured as the advisor (.claude/settings.json).
- Consult the advisor before starting each milestone's plan, when the same error recurs twice,
  and before declaring a milestone done.
