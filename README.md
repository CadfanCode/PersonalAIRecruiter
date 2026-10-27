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

## Running it

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

Model and cost: JobPilot runs your own `claude` CLI. Without `JOBPILOT_CLAUDE_MODEL` it uses the
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

Status: milestones 1 (skeleton), 2 (CV database), 3 (search and ranking), 4 (documents) and 5 (apply) built. The
real-site acceptance of milestone 5 (Ponty, Workday, Varbi) is yours to run; see docs/adr/0005-apply.md. See `docs/SPEC.md` section 15.
