# 0007 Desktop packaging and subscription usage

Date: 2026-10-27. Status: accepted.

- **One process, one port.** The installed app serves the built UI from the backend (`jobpilot.web-dir`) on
  `127.0.0.1:18080`, so there is no Vite server and no CORS. Client-side routes fall back to `index.html`; an unknown
  `/api/...` path stays a 404.
- **Launcher, not a bundle.** `packaging/jobpilot` starts the database (`docker compose up --wait`), the Spring Boot jar,
  and a Chromium-based app window with its own profile. When that window closes it stops the jar (SIGTERM, so the
  Playwright browser closes too) and the database container. The data volume is the same one the development compose file
  uses. A second click on the icon opens another window and leaves shutdown to the first launcher.
- **Docker stays.** Embedding PostgreSQL would change the database layer for little gain; the launcher reports clearly
  when Docker is not running.
- **Usage comes from the CLI.** The subscription has no per-call price, so dollar figures are gone from the UI. The
  session (5 hour) and weekly windows come from the `rate_limit_event` that `claude -p --output-format stream-json
  --verbose` emits, read through `ClaudeRunner.limits()`. No credentials are read and no Anthropic endpoint is called
  (CLAUDE.md rule 6). A reading is one tiny call and is cached for two minutes.
