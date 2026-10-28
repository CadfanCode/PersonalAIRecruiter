# 0014 AI providers and the credential store

Date: 2026-10-28. Status: accepted. Supersedes CLAUDE.md hard rule 6 as written in ADR 0001 ("CLI only").

- **Providers.** `AiProvider` (id, label, `run(ClaudeTask)`, optional `limits()`) has two implementations: `CliClaudeRunner`
  ("cli": the user's Claude Code subscription through `claude -p`) and `ApiClaudeRunner` ("api": an Anthropic API key).
  `ProviderRouter` is the only `ClaudeRunner` bean; every service keeps calling `ClaudeRunner` and the router hands the call to
  the provider chosen in onboarding or Settings (`app_setting.claude.provider`). Another provider is one more bean.
- **The API provider uses plain HTTPS, not an SDK.** `POST /v1/messages` with the key in `x-api-key`. Structured output is
  forced by one tool, `respond`, whose `input_schema` is the task's JSON Schema (`tool_choice` names it); the answer is validated
  against the same schema as the CLI's. Web discovery adds the server-side `web_search_20250305` tool, lets the model pause and
  continue, and asks for the `respond` call if it answers in prose. 429/529/5xx are retried (honouring `retry-after`).
  It shares the one `ClaudeConcurrency` limit with the CLI provider. Tested against a local stand-in server only; the real
  service has not been called from this code.
- **Credentials only in the OS credential store.** `SecretStore` is implemented with java-keyring (macOS Keychain, Windows
  Credential Manager, Linux Secret Service). On a machine without one the app says so and saves nothing: there is no
  plaintext fallback. The API key is never returned by any endpoint (only its last four characters), never logged, and never
  written to the database; `app_setting` holds the choice of provider and model, not secrets.
- **CLI detection and sign-in.** The app finds `claude` in the usual per-user folders on every OS, shows a copy-and-paste
  install command when it is missing, and tells "not installed" from "not signed in" after a real test call. It never performs
  the Claude sign-in itself: the user runs `claude` in a terminal, as before.
- **Saved portal logins** reuse the same store; see ADR 0016.
