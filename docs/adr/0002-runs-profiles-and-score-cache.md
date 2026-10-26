# 0002 Runs, immutable search profiles and the score cache

Date: 2026-10-26. Status: accepted.

## Context

An ad (`job`) has one row per `dedupe_key` across all runs, so it can turn up in many runs. Its
score depends on the SearchProfile of the run and on the contents of the CV database.

## Decisions

- **`run_job` links runs to jobs.** `job.run_id` only records the run that first found the ad.
- **`search_profile` rows are immutable.** Editing a profile before a run continues inserts a new
  row and repoints `pipeline_run.search_profile_id`. Scores are cached by
  `(job_id, search_profile_id, cv_version)`, so an in-place edit would serve stale scores.
- **`cv_version`** is the first 16 hex digits of the SHA-256 of the CV digest JSON that goes into
  the scoring prompt. Any change to entries, facts, skills or known gaps gives a new version.
  Owner `note` fields are not part of the digest, so editing a note does not invalidate scores.
- **Upsert rules.** On a `dedupe_key` collision the source fields are replaced only when the new
  description is longer. `id`, `status`, `notes` and `created_at` are never touched.
- **No transaction spans a Claude call.** The run executes on a bounded pool; every database
  write is its own short statement, so a 5 minute web discovery never holds a connection.
- **Interrupted runs.** On startup, QUEUED and RUNNING runs become FAILED. WAITING_FOR_USER runs
  are left alone because they are waiting for the user, not for a thread.
- **Scoring cap.** `limit` is the number of ads scored (best JobTech relevance first, unverified
  web dates last). Other filtered ads are stored and listed unscored.
