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

Tests: `cd backend && ./mvnw verify` (needs Docker) and `cd frontend && npm test`.

## Privacy

Everything runs on your machine. The browser profile in `~/.jobpilot/browser-profile` (from
milestone 5) holds your logins and cookies, so treat that directory like a password store.
JobPilot never stores passwords. `scripts/backup.sh` dumps the database.

Status: milestone 1 (skeleton) done. See `docs/SPEC.md` section 15.
