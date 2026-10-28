# 0008 The applications basket, batch documents and the fill queue

Date: 2026-10-27. Status: accepted.

The flow from results to a submitted application had unclear transitions: selection lived only in React state, documents were
generated one job at a time, and the apply monitor had no notion of which jobs the user had chosen.

- **`application` table** is the basket: one row per selected job with the run, search mode, role type, and the user's own
  submission confirmation. Documents and Finalize read it. Unsent ticks stay in the browser (per run), so Documents never lists
  jobs that have nothing generated.
- **One batch task.** The pipeline executor has two threads and a short queue that rejects when full, so "create documents for N
  jobs" is one task that walks the jobs, each with its own DOCUMENTS run: a failure is recorded on that job and the rest go on.
  Form filling got its own executor so the two cannot starve each other.
- **Submission is the user's.** The app never clicks submit (rule 1), so it cannot observe a submission. "I submitted it" is a
  deliberate click that sets APPLIED and records which documents were used. The pause before submit is permanent: there is no
  setting to relax it; that would need a deliberate change of rule 1. Logging in Arbetsförmedlingen keeps its separate
  confirmation screen (rule 5), prefilled.
- **Fill queue.** More applications than tabs wait as QUEUED and start as slots free, so "Fill all" works for any number.
- **Errors.** `TabClosedException` maps to 409 with a message, a catch-all handler returns `{error}` instead of a bare 500, and
  tabs closed by hand are reconciled. The installed app could not find `claude` because the desktop launcher's PATH lacked
  `~/.local/bin`: the launcher extends PATH and the backend resolves the executable in the usual per-user directories.
