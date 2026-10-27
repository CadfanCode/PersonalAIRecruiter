# JobPilot

Personal job-application assistant for Cai Birch. It runs locally and uses your own Claude Code
for the AI steps.

Starting prompt -> job search (Platsbanken via JobTech, plus career pages) -> ranked results
table -> tailored CV and cover letter PDFs from your CV database -> pre-filled application forms
(you submit) -> entries saved in your aktivitetsrapport.

- `START_PROMPT.md`: how to kick off the build in Claude Code
- `CLAUDE.md`: project rules Claude Code follows on every session
- `docs/SPEC.md`: the full specification and milestones
- `seed`: your CV database seed, the original CV and Knowit letter, and the PDFs sent on
  26 Oct 2026 as references

## Install as a desktop app (Linux)

```
packaging/install.sh
```

This builds JobPilot and installs a launcher named **JobPilot** in your application menu (pin it to the taskbar like
any other app). Opening it starts PostgreSQL (docker) and the backend and shows the UI in its own window (Brave, or
another Chromium-based browser); closing the window stops both. It needs Java 21+, Docker, Node.js (only to build)
and the `claude` CLI. Re-run the script to update; `packaging/uninstall.sh` removes it and keeps your data. Logs are
in `~/.jobpilot/logs`; optional settings such as `JOBPILOT_CLAUDE_MODEL=opus` go in `~/.config/jobpilot/env`. The app
listens on `127.0.0.1:18080` (change with `JOBPILOT_PORT`). The development setup below still works.

## Running it (development)

```
docker compose up -d                      # PostgreSQL 16 on 127.0.0.1:5432
cd backend && ./mvnw spring-boot:run      # API on http://127.0.0.1:8080
cd frontend && npm install && npm run dev # UI on http://localhost:5173
```

`./mvnw` needs a valid `JAVA_HOME` (Java 21 or newer). If port 8080 is taken, start the API with
`SERVER_PORT=8081` and the UI with `VITE_API_TARGET=http://127.0.0.1:8081`.

Applying (milestone 5): install Chromium once with
`cd backend && ./mvnw exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"`.
"Open application" starts a separate Chromium with its own profile in `~/.jobpilot/browser-profile` (never
your everyday browser). Sign in to Varbi, Jobshark and similar sites there; the profile keeps the session.
JobPilot fills what it can and stops: it never submits, never types passwords or handles BankID, never ticks
consent boxes and never clicks Ansök. Press Submit yourself. `JOBPILOT_BROWSER_HEADLESS=true` runs it without a
window (tests do this). Port 8080 conflicts: see above.

Aktivitetsrapport (milestone 6): mark a job as applied, select it and choose "Log in aktivitetsrapport". You
check all seven values first (yrkesroll, employer, omfattning, var, ort, annons, datum), then JobPilot fills the
form in the JobPilot browser, checks every value, clicks Spara once per job and waits for "Din aktivitet är
sparad". You must be signed in with BankID in that browser. It never submits the monthly report (Skicka in).
If the confirmation does not appear, it stops and tells you to check by hand. The page markup of the real site
is unverified; see docs/adr/0006-aktivitetsrapport.md.

Usage: the dashboard and settings show your Claude subscription's session (5 hour) and weekly usage with reset times,
as reported by the CLI itself (one tiny call per reading, cached for two minutes). No dollar amounts are shown.

Model: JobPilot runs your own `claude` CLI. Without `JOBPILOT_CLAUDE_MODEL` it uses the
CLI's default model (Opus on this machine). Measured on 26 Oct 2026: scoring one ad costs about
$0.04 on Sonnet (`JOBPILOT_CLAUDE_MODEL=sonnet`), and a run scores up to 50 ads by default. The
cost at the default model has not been measured but is several times higher, so set the variable
unless you want Opus. Totals are shown on the dashboard and at `/api/claude/usage`.

Tests: `cd backend && ./mvnw verify` (needs Docker) and `cd frontend && npm test`.

## Privacy

Everything runs on your machine. The browser profile in `~/.jobpilot/browser-profile` (from
milestone 5) holds your logins and cookies, so treat that directory like a password store.
JobPilot never stores passwords. `scripts/backup.sh` dumps the database.

Loading your CV data: with the API running, `curl --data-binary @seed/cv_database_seed.json
-H 'Content-Type: application/json' http://127.0.0.1:8080/api/cv/seed` (or use the file picker on
the CV database page when it is empty). It refuses to run on a non-empty database.

Status: milestones 1 (skeleton), 2 (CV database), 3 (search and ranking), 4 (documents), 5 (apply) and 6
(aktivitetsrapport) built. The real-site acceptance of milestones 5 and 6 (Ponty, Workday, Varbi, the
aktivitetsrapport) is yours to run; see docs/adr/0005-apply.md and 0006. See `docs/SPEC.md` section 15.
