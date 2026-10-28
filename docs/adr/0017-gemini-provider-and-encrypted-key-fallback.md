# 0017 Gemini replaces the Anthropic API provider; model selectors; encrypted fallback for the API key

Date: 2026-10-28. Status: accepted. Amends CLAUDE.md hard rule 6 and ADR 0014 (which named the Anthropic API).

- **Providers.** `AiProvider` ids are now `cli` (Claude Code subscription) and `gemini` (Google AI Studio). `ApiClaudeRunner` and
  the `api.*` settings are gone. The `ClaudeRunner` / `ProviderRouter` names stay: renaming them would be churn, and the
  abstraction already did its job. A database that holds provider `api` is read as "not chosen", so that person connects again.
- **Gemini over plain HTTPS.** `POST /v1beta/models/{model}:generateContent`, key in the `x-goog-api-key` header. Structured output
  uses `generationConfig.responseMimeType=application/json` with `responseJsonSchema`, which takes our JSON Schemas as they are
  (verified live 2026-10-28; `pattern` and `additionalProperties` are accepted), minus `$schema`/`$id`. The answer is validated
  against the same schema as the CLI's. Web discovery adds `tools:[{google_search:{}}]` in the same call; if the API answers 400 for the
  combination, it is done in two calls (grounded search, then structuring). Not verified live: the grounding quota of the test key
  was exhausted. Busy answers (503 "high demand" was seen on the Flash aliases) are retried five times with growing pauses.
- **Models.** Gemini default is the alias `gemini-flash-latest`; the selector lists what the key can call (`GET /models`, text
  models only). Claude Code uses `--model <alias>` (`sonnet`, `opus`, `haiku`, `fable`, or blank for the CLI default), stored as
  `claude.model` and read on every request, so a change applies to the next call. Aliases follow the newest model of each family.
- **The key.** Kept in the OS credential store when there is one. Without one, an AES-GCM encrypted file in
  `<data-dir>/secrets` whose random key sits beside it with owner-only permissions: out of plain text and out of the database, but
  it only guards against other users and casual reading, not against code running as the same user. Saved portal passwords never
  use the fallback (rule 2): they stay in the credential store only. The key is sent only in a header and is never logged; the
  status call never reads it (only its last four characters, kept in `app_setting`).
