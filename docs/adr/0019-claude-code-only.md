# 0019 Claude Code is the only AI provider

Date: 2026-10-28. Status: accepted. Supersedes ADR 0017 (the Gemini provider and the encrypted key fallback) and amends CLAUDE.md hard rule 6.

- **Decision.** JobPilot uses the user's Claude Code subscription and nothing else. The Google AI Studio (Gemini) provider, its model
  selector, its API key handling and the AES-GCM encrypted-file fallback (`ApiKeyVault`) are removed. No API key of any kind is
  handled for the AI.
- **Kept.** The `ClaudeRunner` interface (hard rule 6) and the `AiProvider` / `ProviderRouter` seam, now with one provider. Saved
  portal passwords were never part of the fallback and still live only in the OS credential store.
- **Existing installs.** A stored provider `gemini` (or `api`) is read as `cli`, a stored passed test for it no longer counts, and the
  person confirms Claude Code once in setup. A key left in the credential store by an older version is no longer read by anything.
- **API.** `PUT /provider` accepts only `cli`; `POST /test` takes no body; the `gemini-*` endpoints are gone.
