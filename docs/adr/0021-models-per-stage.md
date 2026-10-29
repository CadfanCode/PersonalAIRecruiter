# 0021 A Claude model per stage

Date: 2026-10-28. Status: accepted. Extends ADR 0019.

- **Decision.** Settings has a default model and one optional model per stage: Search, Scoring, Reading your CV, CV and cover letter,
  Auto-fill. `ModelStage` maps each Claude task name to its stage; `ProviderSettings.modelFor(task)` returns the stage's choice, else
  the default (`claude.model`). Stored as `claude.model.<STAGE>` in `app_setting`. `CliClaudeRunner` asks per task, on every call.
- **Why these stages.** Scoring is the high-volume call, so it is its own row: a lighter model there saves the most. Writing documents and
  filling forms reward a stronger model. The connection check and the usage check always use the default.
- **Guard.** A test lists every prompt template and fails if one has no stage, so a new task cannot silently run on the default model.
- **API.** `PUT /api/claude/connection/stage-model/{stage}`; the connection status lists the stages with their current choice.
