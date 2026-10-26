# JobPilot

Local, single-user web app that runs a job-application pipeline for one person:
starting prompt -> job search -> ranked results table -> tailored CV and cover letter (PDF)
-> browser form filling (stops before submit) -> logging in Arbetsförmedlingen's aktivitetsrapport.

The full specification is in `docs/SPEC.md`. Read it before starting any milestone, and keep it
up to date when a decision changes.

## Stack

- Backend: Java 21, Spring Boot 3.x (latest stable), Spring Web, Spring Data JDBC, Flyway, PostgreSQL 16
- Frontend: React 19, TypeScript, Vite, Tailwind CSS, TanStack Query, TanStack Table
- Browser automation: Playwright for Java, headed Chromium with a persistent profile
- PDF: openhtmltopdf (HTML + CSS -> PDF)
- AI: the user's own local Claude Code CLI, called headless (`claude -p`). No API key, no Anthropic SDK.
- Tests: JUnit 5, Mockito, Testcontainers (PostgreSQL), Vitest, React Testing Library

## Layout

```
backend/    Spring Boot app (Maven wrapper)
frontend/   Vite + React app
docs/       SPEC.md, ADRs (docs/adr/NNNN-title.md)
seed/       cv_database_seed.json (the user's CV facts)
docker-compose.yml   PostgreSQL only
```

## Commands

- `docker compose up -d` starts PostgreSQL
- `cd backend && ./mvnw spring-boot:run` starts the API on http://localhost:8080
- `cd backend && ./mvnw verify` runs all backend tests
- `cd frontend && npm run dev` starts the UI on http://localhost:5173 (proxies /api to 8080)
- `cd frontend && npm test` and `npm run build`
- `cd backend && ./mvnw exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"` installs the browser once

## Hard rules (never break these)

1. Never click a submit, send or apply button in any external site. The user submits by hand.
   Enforce this in code (see SPEC section 9.3), not only in prompts.
2. Never type into password fields, never create accounts, never handle BankID. When a login or
   BankID screen appears, pause and show "Waiting for you to sign in" in the UI.
3. Never tick consent, GDPR, terms or "join talent pool" boxes. Leave them for the user.
4. CVs and letters may only contain facts that exist in the CV database. Claude selects and
   rephrases; it never invents employers, dates, tools, degrees or results.
5. The aktivitetsrapport automation only saves activities ("Spara"). It never submits the
   monthly report.
6. Claude is only reached through `ClaudeRunner` (the local CLI). Do not add the Anthropic API,
   an API key, or any Claude.ai login flow. This is a personal tool using the user's own
   Claude Code subscription.

## Writing rules for generated CVs and letters

These are enforced by `DocumentStyleValidator` after every generation, and violations trigger
one automatic rewrite before failing:

- No em-dashes or en-dashes (U+2014, U+2013). Use commas, colons or full stops.
- Do not say the candidate is newly qualified, a new graduate, "nyexaminerad", "recently
  graduated" or "i början av karriären" unless the job ad explicitly asks for new graduates
  (`job.asksForNewGraduates == true`).
- Avoid typical AI phrasing (banned list in SPEC 8.4). Natural, specific, plain language.
- Write in the ad's language: Swedish ad -> Swedish documents, English ad -> English.
- Cover letter shape: friendly, humble but professional opening (who he is by expertise, why
  this role, what qualifies him; never "My name is" / "Jag heter"), one concrete example,
  relevant experience with any real gaps stated honestly, team and personal paragraph,
  short thank-you, sign-off.
- CV profile opens with specialisation and evidence, not with the degree or its date.

## Conventions

- Package root `se.caibirch.jobpilot`. Feature packages: `profile`, `cvdb`, `search`, `jobs`,
  `ranking`, `documents`, `apply`, `aktivitet`, `claude`, `pipeline`, `settings`.
- Constructor injection, records for DTOs, no Lombok.
- Every Claude call has a JSON Schema in `backend/src/main/resources/claude/schemas/` and a
  prompt template in `backend/src/main/resources/claude/prompts/`. Never parse free text.
- Long-running work runs as `PipelineRun` steps on a bounded executor; the UI polls
  `/api/runs/{id}`.
- Record architecture decisions as ADRs in `docs/adr/`.
- Keep the UI in English with Swedish domain terms where they are proper names
  (Platsbanken, aktivitetsrapport, yrkesroll).
- Tests: unit tests mock `ClaudeRunner`; never call the real CLI in automated tests.
  Integration tests use Testcontainers. Playwright tests run against local HTML fixtures in
  `backend/src/test/resources/fixtures/`, never against real sites.
