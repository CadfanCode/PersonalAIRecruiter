# JobPilot specification

Version 2.0, 28 October 2026 (the overhaul, sections 2.2, 4.2, 5.5, 9.5, 10.1 and 17; the rest is as of 1.0 and 27 October). Owner: Cai Birch.

## 1. Goal

A desktop app (everything stays on the user's computer, for any profession) that turns one starting prompt ("new-grad Java roles in Stockholm posted in the
last 7 days", "PE teacher roles in Nacka", "logistics coordinator, Uppsala, part time") into:

1. a ranked, polished results table of matching job ads,
2. a CV and cover letter per chosen ad, tailored from a personal CV database, as PDFs,
3. pre-filled application forms in separate browser tabs, stopped before submit,
4. saved entries in Arbetsförmedlingen's aktivitetsrapport for the jobs the user marks as applied.

The starting prompt is the single source of intent. It produces a `SearchProfile` that every
later step reads, so changing the role (developer -> PE teacher) changes search terms, ranking
criteria, which CV facts are selected, the document tone and the yrkesroll used in the
aktivitetsrapport.

Out of scope for v1: multi-user, hosting, email sending, auto-submit, LinkedIn scraping.

## 2. User flow

1. **CV database** (one-time, then maintained): import the existing CV PDF and letters; Claude
   splits them into atomic facts; the user reviews, edits, tags and adds missing history
   (for example teaching jobs).
2. **New run**: the user writes a starting prompt and optional constraints (location, max age of
   ads, number of results, languages). Claude produces a `SearchProfile`; the user can edit it
   before searching.
3. **Search**: JobTech JobSearch API (Platsbanken) plus optional web search of company career
   pages. Results are de-duplicated and stored.
4. **Rank**: each ad is scored against the CV database. The results table shows score, fit, gaps,
   deadline, language, source and status.
5. **Tailor**: for selected jobs, Claude picks the relevant facts and writes the CV and letter in
   the ad's language. The validator checks the style rules. The user previews, edits and
   approves, and PDFs are rendered.
6. **Apply**: "Open applications" launches a headed browser with a persistent profile, one tab per
   job, and fills what it can. It pauses at logins and BankID and never submits.
7. **Log**: when the user marks a job "Applied", JobPilot can save it to the aktivitetsrapport
   (the user must already be signed in with BankID in the JobPilot browser profile).

### 2.1 The guided pipeline (2026-10-27)

The UI is one pipeline with four stages, each ending in one obvious action:

1. **New run** (three modes): *Targeted search* (the prompt above), *Discover roles* (section 6.3) or *Paste a job link*
   (section 6.4). All three end in a ranked results table.
2. **Results**: the user ticks jobs (remembered per run in the browser). The selection bar shows what is selected, lets the user
   deselect, and offers "Create tailored documents for N selected jobs": one batch writes a CV and a letter per job (one
   failure does not stop the others) and opens Documents.
3. **Documents**: progress and retry per selected job, the unchanged editor and PDF preview per job, "Ask Claude" chat
   (section 8.6). Each application with both documents approved has a checkbox ("Send to Apply", remembered in the browser,
   nothing ticked by default); "Apply (N)" sends exactly the ticked ones on (`POST /api/applications/apply {jobIds}`, which
   marks them `sent_to_apply_at`). The rest stay in Documents.
4. **Finalize Applications** (was "Apply monitor"): only the applications sent on from Documents, each with its documents and status
   ("Take out of Apply" returns one to Documents; `DELETE /api/applications/{jobId}/apply"); "Fill all" fills the
   forms, at most five browser tabs at a time, the rest queue. The pause before submit is permanent (CLAUDE.md rule 1).
   The user submits by hand and confirms with "I submitted it", which records the application (job, company, role type,
   search mode, date, documents used) for the Activity Report. Logging in Arbetsförmedlingen stays a separate, prefilled
   confirmation step (section 10).

### 2.2 The product (2026-10-28, ADRs 0012 to 0016)

JobPilot is installed like any app (one installer per OS, no Docker, no database or server to set up) and is for any profession.

**First run (onboarding)**, one step per screen with a progress indicator, shown once: (1) drop or browse the CV (PDF, DOCX, TXT,
MD); the files are held in the browser and read after step 2, because reading needs Claude; (2) connect Claude: the Claude Code
subscription (found in the usual folders on every OS, with an install command when missing, and "not installed" told apart
from "not signed in"); there is no other AI option (ADR 0019); a real test call must pass before
continuing; then the CV is read, a short summary of what was found is shown and confirmed (the person's contact details fill
only blank profile fields); (3) the country (Sweden live, others "coming soon"); (4) the search mode.
`GET /api/onboarding` reports the state and the first open step. While setup is open, the four steps *are* the bar across the top and
each is a button: steps up to the first one still to do open freely (back and forth), a later one shows "<step> has not been started
yet. Please complete <first step> first." Once setup is finished `/welcome` redirects to Mode and the steps are never shown again: the
same things are changed from the bottom menu (CV database for new CV files, Country, Claude). Everything setup holds in the browser
(chosen files, step, the CV reading in progress) lives in `SetupProvider` above the routes, so opening the menu in the middle of
setup loses nothing (ADR 0020).

**The pipeline**, five stages across the top, every one a link (current underlined, completed ones ticked; a stage with nothing in it
yet opens `/not-started/{stage}`, which says "<stage> has not been started yet. Please complete <earlier stage> first." with a button to it): *Search mode* (Discovery or
Prompt; paste-a-link is a secondary action) -> *Run search* (progress with percent and time left, results appear as they are
scored, the selection basket) -> *Documents* (a tailored CV and cover letter per selected job; the overview shows "CV approved" and
"Cover letter approved" per job, "Approve all", and an **Apply** button) -> *Apply* (a browser tab per approved application,
filled in; per-application status: filling, needs login, needs attention, ready for submission, failed) -> *Submit* (the user
presses the employer's own submit button and confirms; the application is stored with a snapshot). Only applications whose CV
and cover letter are **both** approved go on to Apply; the UI says which do not and why. A menu at the bottom edge, on every screen including setup, holds
CV database, Country, Claude, Submitted applications, Extra features (the activity report) and Settings; it opens on hover at the
bottom edge, on the "Menu" handle (click or focus) and when Tab reaches it. Using it only changes the page: searches, documents and
applications run on the server, and text typed into forms, the open document's draft and chat, a CV being imported and the activity-report
run are kept in the browser while the window is open (`useDraft`).

**Prompt mode**: a text box with tips and examples from several professions; ads to score default to 20 (cap 150); recency and
place come from the words; the search plan (the `SearchProfile`) is always shown for review and approving it starts the search.
Platsbanken is always searched; employer career pages are an opt-in Settings choice. **Discovery mode**: the profile is made
and accepted for the user; they only pick roles (section 6.3), optionally "go wider" (the aggressive option).

## 3. Architecture

```
React UI (served by the API in the installed app; 5173 in development) --REST/JSON--> Spring Boot API (18080 installed, 8080 dev)
                                   |                                                       --JDBC--> embedded PostgreSQL 16
                                   +-- ClaudeRunner ---- ProviderRouter --> `claude -p` CLI (ADR 0019)
                                   +-- CountryRegistry -- JobSource ------> one job board per country (Sweden: JobTech/Platsbanken)
                                   +-- SecretStore ------ java-keyring ----> OS credential store (API key, saved portal logins)
                                   +-- JobTechClient --- HTTPS ------------> jobsearch.api.jobtechdev.se
                                   +-- BrowserService -- Playwright -------> headed Chromium (persistent profile)
                                   +-- PdfRenderer ----- openhtmltopdf ----> ~/.jobpilot/documents/*.pdf
```

Single process and single user. Bind to 127.0.0.1 only. No authentication on the local API, but
reject requests whose `Origin` is not the app's own address or the development UI's
(CSRF protection for a localhost app). The installed app is one jpackage image with its own Java runtime:
`DesktopLauncher` takes a single-instance lock, picks the first free port from 18080, starts the database and the API and opens
the UI in its own window of any Chromium-based browser (Playwright's Chromium as the fallback); closing the window stops it
(ADR 0012).

Data directory: `~/.jobpilot/` (configurable) containing `db/` (the embedded PostgreSQL data), `documents/`,
`browser-profile/`, `app-window/` and `logs/`.

## 4. Data model (PostgreSQL, Flyway migrations)

```
profile(id, full_name, headline, email, phone, city, website, github, linkedin,
        languages_json, created_at, updated_at)

cv_entry(id, kind, title, organisation, location, start_ym, end_ym, ongoing,
         summary, sort_order, hidden, created_at, updated_at)
  -- kind: EDUCATION | WORK | INTERNSHIP | PROJECT | OWN_BUSINESS | VOLUNTEER |
  --       CERTIFICATION | AWARD | PUBLICATION | CONFERENCE | LANGUAGE | OTHER

cv_fact(id, entry_id -> cv_entry, text_en, text_sv, evidence, metrics, sort_order)
  -- one atomic, verifiable statement ("Found and fixed race conditions in logging flows")

skill(id, name, category, level, years, last_used_ym)
fact_skill(fact_id, skill_id)
tag(id, name)                    -- e.g. software, java, security, teaching, sport-science, research
entry_tag(entry_id, tag_id)
fact_tag(fact_id, tag_id)

style_rule(id, rule_key, rule_text, enabled)      -- seeded from CLAUDE.md writing rules

search_profile(id, prompt, profile_json, created_at)
  -- profile_json: see 5.4 SearchProfile schema

pipeline_run(id, search_profile_id, status, step, progress, message, started_at, finished_at)
  -- status: QUEUED | RUNNING | WAITING_FOR_USER | DONE | FAILED | CANCELLED

job(id, run_id, source, source_id, url, apply_url, title, employer, client_employer,
    location, municipality, published_at, deadline, language, employment_type,
    description_text, raw_json, asks_for_new_graduates, experience_required_text,
    dedupe_key UNIQUE, status, created_at)
  -- status: NEW | SHORTLISTED | DOCS_READY | FILLED | APPLIED | LOGGED | REJECTED | ARCHIVED

job_score(job_id, total, tech, experience, eligibility, fit_text, gaps_text,
          selected_fact_ids_json, model, scored_at)

document(id, job_id, kind, language, content_json, html, pdf_path, version,
         validator_report_json, approved, created_at)
  -- kind: CV | LETTER

application_form_snapshot(id, job_id, url, fields_json, actions_json, created_at)

aktivitet_log(id, job_id, yrkesroll, employer_label, municipality, omfattning,
              answered_ad, applied_date, saved_at, result, message)

-- added in V2 (milestone 2), to hold the whole seed:
--   profile: phone_international, work_authorisation
--   cv_entry: title_en, note (note is an owner to-do, internal only, never sent to a prompt)
--   start_ym / end_ym hold "YYYY-MM", or "YYYY" when the month is unknown
known_gap(id, text, sort_order)
answers_memory(id, question_key UNIQUE, question, answer, updated_at)
  -- question_key = lowercase, punctuation stripped, whitespace collapsed

claude_call(id, task, model, started_at, duration_ms, cost_usd, input_tokens, output_tokens,
            cache_read_tokens, cache_creation_tokens, success, error_message)
  -- one row per Claude CLI call (5.1)
```

Tables for settings and the answers memory are added by the milestones that need them.

`dedupe_key` = lowercase(employer + "|" + normalised title + "|" + municipality). Keep the
source with the richest description when duplicates collide.

### 4.1 Additions of 2026-10-27 (migrations V6 to V8)

```
pipeline_run.mode (TARGETED | DISCOVER | MANUAL), pipeline_run.source_url (MANUAL: the pasted address)
application(job_id PK -> job, run_id, search_mode, role_type, selected_at, submitted_at, docs_json)
  -- the pipeline basket. submitted_at is set only by the user's own "I submitted it" click; docs_json records the document
  -- ids, versions and file names used then.
role_suggestion(id, run_id, title, category RELATED|HYBRID|OUTSIDE_FIELD|CUSTOM, match_score, reasoning, gaps, selected,
                user_added, profile_json, search_profile_id, sort_order)
run_job.role_suggestion_id          -- the role an ad was scored and is tailored against (its best-relevance role)
run_job_role(run_id, job_id, role_suggestion_id)   -- every role that found the ad
```

### 4.2 Additions of 2026-10-28 (migrations V9 to V11)

```
app_setting(key, value, updated_at)      -- claude.provider, claude.tested, claude.concurrency, api.model, country, search.mode,
                                            search.careerPages, onboarding.complete
profile: security_vetting dropped;  job_score.tech renamed skills;  cv_entry.kind adds LICENCE and COURSE;
role_suggestion.category OUTSIDE_TECH renamed OUTSIDE_FIELD
pipeline_run.step_started_at             -- when the current step began (time-left estimate while scoring)
submitted_application(id, job_id -> job ON DELETE SET NULL, title, employer, municipality, url, apply_url, language, ad_text,
   search_mode, role_type, score_total, submitted_at, cv_version, cv_file, cv_content_json, cv_pdf BYTEA,
   letter_version, letter_file, letter_content_json, letter_pdf BYTEA)
   -- written once, when the user confirms they submitted; never changed by later edits, regeneration or undo of the document
saved_login(host PK, username, created_at, last_used_at)   -- the password is in the OS credential store, not here
```

## 5. Claude integration

### 5.1 ClaudeRunner

All AI work goes through `ClaudeRunner`; `ProviderRouter` hands each call to the provider the user chose (section 5.5). The CLI provider shells out to the user's local Claude Code:

```
claude -p <prompt>
  --output-format json
  --json-schema <schema-json>
  --tools ""                          # no tools for pure text tasks
  --no-session-persistence
  --disable-slash-commands --strict-mcp-config --setting-sources project
  [--tools WebSearch,WebFetch --allowedTools WebSearch WebFetch]   # only for web discovery (6.2)
  [--model <settings.claude.model>]
```

Verified behaviour (Claude Code 2.1.x): with `--output-format json --json-schema ...` the CLI
prints one JSON object whose `structured_output` field holds the schema-valid result. Also read
`is_error`, `subtype` (expect `"success"`), `result`, `total_cost_usd`, `usage` and
`duration_ms`. Redirect stdin from `/dev/null` (otherwise the CLI waits 3 s for stdin).

The last three flags (decided in milestone 1) stop the CLI from loading the user's plugins,
skills, MCP servers and user-level settings. Measured on 26 Oct 2026: a tiny call dropped from
about $0.40 (50k cache tokens) to about $0.03 (3k tokens) when run by the app. The process runs in `<data.dir>/claude-work/`, which
has no CLAUDE.md or project settings, so nothing else is loaded.

Implementation requirements:
- `ProcessBuilder` with an argument list (never a shell string). Prompt via an argument, or via
  stdin with `--input-format text` if it exceeds the argument length limit (use stdin when the
  prompt is over 100 kB).
- Configurable binary path (`settings.claude.path`, default `claude` on PATH), timeout (default
  180 s, web discovery and CV import 600 s), and max concurrency (default 4, range 1 to 8, one shared limiter
  `ClaudeConcurrency` behind every model call; changeable in Settings while the app runs).
- Parse with Jackson. If `is_error` is true, `subtype` is not success, the exit code is not
  zero, or `structured_output` is missing, throw `ClaudeCallException` with stderr attached.
- Validate `structured_output` against the same schema again (networknt json-schema-validator).
- Log every call (task name, duration, cost, tokens, success) to the `claude_call` table (added
  to the V1 migration; it is not in section 4's list) and show
  totals on the Settings page so the user can see usage against his subscription.
- Health check endpoint `/api/claude/health` runs `claude --version` and a tiny schema call.
- Interface `ClaudeRunner { <T> T run(ClaudeTask<T> task) }` with a `FakeClaudeRunner` for
  tests that returns fixture JSON per task name.

### 5.2 Prompt templates

Prompts live in `resources/claude/prompts/*.md` with `{{placeholders}}`, rendered by a tiny
template helper. Each prompt states the role, the inputs (as fenced JSON blocks), the rules, and
"Return only data matching the schema". Every prompt that writes documents includes the
enabled `style_rule` texts verbatim.

### 5.3 Tasks

| Task | Input | Output schema |
|---|---|---|
| `importCv` | extracted text of CV/letters | entries[] with facts[], skills[], tags[] |
| `buildSearchProfile` | starting prompt, profile summary, today's date | SearchProfile |
| `discoverJobsWeb` | SearchProfile (tools: WebSearch, WebFetch) | jobs[] with url, title, employer, published date, deadline, location, language, description |
| `scoreJob` | SearchProfile, job ad text, CV database digest | JobScore |
| `selectFacts` | job ad, SearchProfile, full CV database | selected entry ids, fact ids, ordering, reasons |
| `writeCv` | job ad, selected facts, profile, style rules, language | CvContent |
| `writeLetter` | job ad, selected facts, profile, style rules, language, example letter | LetterContent |
| `rewriteForViolations` | document JSON, validator report | same schema as input |
| `browseApplication` | accessibility snapshot of the page (no typed values), page facts, profile, job, available files, answers memory, CV digest, history | FormActions.Step |

### 5.4 Key schemas (abbreviated, write full JSON Schema files)

SearchProfile:
```json
{
  "roleSummary": "string",
  "titles": ["string"],
  "synonyms": ["string"],
  "skillsWanted": ["string"],
  "seniority": "student|graduate|junior|mid|senior|any",
  "acceptExperienceUpToYears": 0,
  "locations": [{"name": "string", "municipality": "string", "radiusKm": 0}],
  "remoteOk": true,
  "languages": ["sv", "en"],
  "maxAgeDays": 7,
  "employmentTypes": ["heltid", "deltid"],
  "exclusions": ["string"],
  "yrkesrollForAktivitetsrapport": "string",
  "relevantTags": ["string"],
  "jobtechQueries": ["string"]
}
```

`locations` holds one entry per municipality the candidate could commute to: `buildSearchProfile`
expands a city such as Stockholm into its neighbours (Solna, Järfälla, Nacka and so on), because
ads sit in those municipalities. The user can edit the list before the search runs.

`relevantTags` drives fact selection. A PE teacher profile might give
`["teaching", "sport-science", "coaching", "pedagogy"]`; a developer profile gives
`["software", "java", "security", "devops"]`. `yrkesrollForAktivitetsrapport` defaults from the
role (for example "Systemutvecklare/Programmerare", "Idrottslärare") and is editable.

JobScore (same rubric used by hand on 26 Oct 2026):
```json
{
  "tech": 0,        "techMax": 50,
  "experience": 0,  "experienceMax": 30,
  "eligibility": 0, "eligibilityMax": 20,
  "asksForNewGraduates": false,
  "experienceRequiredText": "string",
  "language": "sv|en",
  "fit": "string (max 300 chars)",
  "gaps": "string (max 300 chars)",
  "dealBreakers": ["string"]
}
```
- tech (0-50): how well the candidate's skills cover the must-haves, then the merits.
- experience (0-30): how the candidate's real experience matches the requested level. A role
  asking for 5+ years scores low regardless of skills.
- eligibility (0-20): education requirements, citizenship or security clearance, location,
  language, driving licence and similar hard requirements.
- `total` is computed in Java (sum), never by Claude. Rank by total, then by deadline.
- `asksForNewGraduates` is true only if the ad explicitly targets new graduates
  (nyexaminerad, graduate programme, "recently graduated"). "Junior" alone is not enough. Java
  also sets the flag when the ad text matches `NewGraduatePhrases`, the same list the style
  validator uses (the scorer missed "i början av din karriär" in the Comstream ad).

CvContent:
```json
{
  "language": "sv|en",
  "headline": "string",
  "profileParagraphs": ["string"],
  "sections": [
    {"heading": "string", "kind": "SKILLS|EXPERIENCE|PROJECTS|EDUCATION|ACHIEVEMENTS|LANGUAGES",
     "items": [{"entryId": 0, "title": "string", "meta": "string", "dateLabel": "string",
                "bullets": [{"text": "string", "factIds": [0]}]}]}
  ]
}
```
Every bullet must cite at least one `factId`. `FactCitationValidator` rejects bullets whose
factIds are unknown or belong to entries that were not selected.

LetterContent:
```json
{
  "language": "sv|en",
  "subject": "string",
  "subline": "string",
  "greeting": "string",
  "paragraphs": ["string"],
  "closing": "string",
  "signature": "string",
  "factIdsUsed": [0]
}
```

FormActions:
```json
{
  "actions": [
    {"fieldId": "string", "action": "fill|select|check|uncheck|upload|skip",
     "value": "string", "file": "cv|letter|none", "reason": "string",
     "needsUser": false, "source": "profile|memory|cv|document|none"}
  ],
  "questionsForUser": [{"fieldId": "string", "question": "string"}]
}
```

### 5.5 Providers (ADR 0014)

`AiProvider` has one implementation, `cli` (above). The `AiProvider` / `ProviderRouter` seam stays so the rest of the app only ever
sees `ClaudeRunner`. The call limit (`ClaudeConcurrency`, default 4, range 1 to 8, Settings), the models (`claude.model` is the default, `claude.model.<STAGE>` one per stage, see ADR 0021; each is a Claude Code
alias such as `sonnet`, `opus`, `fable`, passed as `--model` and read on every request) and the last passed connection test are in
`app_setting`. A database that still says provider `api` or `gemini` (both removed) is read as `cli` and "not chosen", so the person
confirms Claude Code once more. `GET/PUT /api/claude/connection...` serve onboarding and Settings.

## 6. Job search

### 6.1 JobTech JobSearch API (primary, Platsbanken)

- Base URL `https://jobsearch.api.jobtechdev.se`, endpoints `GET /search` and `GET /ad/{id}`.
  Docs: https://jobsearch.api.jobtechdev.se and https://jobtechdev.se.
- Verified against the live OpenAPI spec (`/swagger.json`, there is no `/openapi.json`) on
  26 Oct 2026: **no api-key header is required**; the spec declares no security scheme and
  anonymous calls succeed. An optional key from settings is still sent as `api-key` when set.
- Parameters used: `q` (free text, one request per entry in `jobtechQueries`), `published-after`
  (accepts `YYYY-MM-DDTHH:MM:SS` without zone, or a number of minutes; we send minutes =
  maxAgeDays x 1440 so no time zone is involved), `limit` (max 100), `offset` (0-2000, paging),
  `sort=pubdate-desc`, `municipality` (repeatable; takes a taxonomy concept id such as
  `AvNB_uwa_6n6` for Stockholm, or the 4-digit municipality code), and `remote=true` for the
  second pass when `remoteOk` is set. `resdet=full` is the default. Search results come back in
  `hits[]` with `total.value`.
- Municipality names are resolved to concept ids through the JobTech Taxonomy API,
  `GET https://taxonomy.api.jobtechdev.se/v1/taxonomy/main/concepts?type=municipality&limit=400`
  (no key; entries have `taxonomy/id` and `taxonomy/preferred-label`). Names that do not resolve
  are searched nationwide and removed by the Java location pre-filter. `radiusKm` is not applied
  to the API call in v1 (it would need coordinates); only the municipality list is used.
- Map fields: `id`, `headline`, `employer.name`, `workplace_address.municipality`,
  `publication_date`, `application_deadline`, `description.text`, `webpage_url`,
  `application_details.url` / `.email`, `employment_type.label`, `working_hours_type.label`,
  `must_have` / `nice_to_have` (skills, languages, work experiences) when present, plus
  `workplace_address.municipality_code`, `experience_required` (boolean) and `removed`.
- Respect rate limits: at most 5 requests per second and exponential backoff on 429 and 5xx.

### 6.2 Company career pages (secondary, optional per run)

`discoverJobsWeb` asks Claude Code (with WebSearch and WebFetch tools allowed) to find ads that
match the SearchProfile on employer career sites and job boards, verifying each ad's publication
date on the ad page itself. Results go through the same normalisation and de-duplication. Mark
`source = WEB` and keep the evidence URL. Ads whose date cannot be verified are flagged "date
unverified" and sorted last.

Lesson from 26 Oct 2026: aggregator snapshot pages (jooble, ledigajobb category pages) give
stale or relative dates, and reposted ads keep old IDs. Trust the ad's own published date.

### 6.3 Discover roles

For users with a broad background. One `suggestRoles` call reads the WHOLE CV digest (education, work, projects,
certifications, languages, skills, tags) and returns role types in three groups (closely related, hybrid, outside tech), each
with a match score, reasoning, gaps and its own search fields. The run pauses in step SUGGEST. The user ticks roles (select
all), renames, and adds roles of their own (their search fields come from one batched `profileRoles` call). Continue then:

- builds one SearchProfile per chosen role on top of the run's base profile (the user's locations, age limit and employment
  types), validated against the same schema as every profile; `relevantTags` are filtered to tags that exist;
- searches each role with the normal collector and prefilter, merges by dedupe key (an ad keeps its best-relevance role as
  primary and is tagged with every role that found it);
- scores at most `limit` ads (default 60, aggressive 150, hard cap 150), round-robin across roles, each ad against its primary
  role's profile. Every later profile lookup for an ad (drawer, tailoring, yrkesroll, application score) goes through the role.

Aggressive: 24 instead of 12 suggestions with a lower bar, a 14-day default age limit, a larger default budget and a lower
default minimum score filter in the results (25 instead of 40). Suggestions stay with the run (`GET /runs/{id}/roles`).

### 6.4 Paste a job link

`POST /import {url, text?}` adds one job the user found themselves. The address is read once, only when allowed: http(s)
only, public addresses only (re-checked at every redirect), size and time limits. Sites in `jobpilot.manual.paste-only-hosts`
(default linkedin.com) are never fetched and never opened by the apply browser (Finalize shows "Apply on the site yourself");
the run waits in step FETCH for the user to paste the ad text. The same wait happens when a page cannot be read or is too thin.
`extractJob` turns the text into an ad (with the municipality spelled as Platsbanken spells it), then the usual profile and
score steps run in a MANUAL run and the job appears in the results like any other. An ad already stored is linked, not
overwritten, and an explicit employer apply link in the ad is used for filling.

A Platsbanken address (`arbetsformedlingen.se/platsbanken/annonser/{id}`) is not fetched as a page (it is drawn by a script and
returns only menu text): the ad is read from the JobTech `GET /ad/{id}` API and mapped like a search hit (`JobTechMapper`), with no
`extractJob` call; a bare host name as apply link gets `https://`. A removed ad fails the run with a clear message. For any other
fetched page that `extractJob` says is not an ad, the run waits for pasted text rather than failing (ADR 0025).

### 6.6 What persists (ADR 0025)

Search runs and their ads are scratch data: cleared at program start, at close and when a new Discovery or Prompt search starts.
An application the user selected stays (with its documents) until deleted or submitted. Submitted applications and the
aktivitetsrapport log are never purged. Deleting applications in Documents (`POST /applications/delete {jobIds}`, after a
confirmation dialog) removes the job, documents, scores and PDF files completely; submitted or still-written ones refuse (409).

### 6.5 Countries (ADR 0013)

`CountryRegistry` lists the known countries; one with a `JobSource` bean is available, the rest show as "coming soon". The chosen
country (onboarding, Settings) decides which `JobSource` searches, how prompts describe the market, and whether the activity report
exists (`activityReport`, Sweden only). Adding a country is one `JobSource` bean and one registry entry.

## 7. Ranking

1. Pre-filter in Java: drop ads older than maxAgeDays, past deadline, matching exclusions, or
   outside locations (unless remote is allowed). Exclusions are whole-word, case-insensitive
   matches against the ad title and employer name only (matching the description would drop
   almost every ad that mentions "senior colleagues"). Location compares the ad's municipality
   with the profile's municipality list; an ad without a municipality is kept.
2. Build a compact CV digest (entries, facts and skills as JSON, at most around 8k tokens) once
   per run.
3. Call `scoreJob` per remaining ad (as many at a time as the concurrency setting allows, default 4). Cache by (job id, CV database version,
   SearchProfile id). The run option `limit` (default 20, at most 150 in every mode) is the number of ads scored, best JobTech
   relevance first, unverified web dates last; other ads that passed the filters are stored and
   listed unscored. Measured 26 Oct 2026 on Sonnet: about $0.04 per scored ad.
4. Default results view: top 10 by total, with a toggle to show all.

## 8. Documents

### 8.1 Fact selection

`selectFacts` chooses entries and facts by relevance to the ad and the SearchProfile's
`relevantTags`. Rules:
- Always include contact details and languages. Education is chosen by relevance like everything else (every completed
  degree stays as one line, the most relevant first; unfinished programmes, duplicates of the same degree and minor
  unrelated courses are left out); if Claude chose none, the newest education entry stays, so a CV never goes out without one. Entries marked
  "letter" only (for example leisure facts) stay out of the CV, and education is never letter only (ADR 0024).
- Prefer recent and relevant entries; omit unrelated ones. A teaching CV drops Spring Boot; a
  developer CV keeps teaching only as a short line that shows communication skills.
- Keep employment gaps honest: an omitted job can be summarised as one line ("Other employment
  2019-2021, available on request") if omitting it would create a gap of more than 6 months.
  Java builds the line from database dates (`GapNote`); separate periods are listed separately
  ("2018-2021 and 2025-2026"), never merged into one range.
- The user can pin or exclude entries per job in the UI before generation.

### 8.2 Generation

After selection, `planDocument` writes an editor's brief (ADR 0024): the field of the ad, one angle for the whole application,
what the ad values, the strongest evidence as points that combine several facts, how the profile opens, which example the
letter tells, the limits to state in the letter and what to keep short. If the plan call fails the writers carry on without it.
The facts are raw material: the writers digest them into new sentences (a bullet may rest on one to four facts of its entry),
order bullets by what the ad values, never strengthen a claim, and state limits only in the letter.

`writeCv` and `writeLetter` receive only the selected facts and the plan and are called at the same time (each with its own validation
and single rewrite); a batch of jobs also runs up to three jobs at once. The model-call limit is the only throttle. Language = the ad's language. The
example letter (the user's Knowit letter, in `seed/`) is passed as a structure example, never as
text to copy.

Java owns every factual header field of the CV. After `writeCv` (and after a rewrite) it overwrites
each item's `title`, `meta` (organisation), `location` and `dateLabel` from the CV database (years
only, plain hyphens), and builds the languages line from the profile. Claude writes prose only: the
headline, the profile paragraphs, bullets and section headings. This removes two failure modes:
invented employers or dates, and en-dashes in date ranges.

### 8.3 Validation (`DocumentStyleValidator`, `FactCitationValidator`)

- Fail on U+2014 or U+2013 anywhere.
- Fail on new-graduate phrases unless `job.asksForNewGraduates`:
  sv: nyexaminerad, nyutexaminerad, nyligen examinerad, nyligen tagit examen,
  i början av (min) karriär; en: newly qualified, new graduate, recent graduate,
  recently graduated, recently finished, fresh graduate, early in my career.
- Fail if the letter opens with "Jag heter" or "My name is".
- Warn (rewrite once) on banned AI phrasing (8.4).
- Fail on bullets without valid fact citations.
- Warn (rewrite once) with `copied_facts` when more than half of the bullets of a CV (at least four) repeat a single cited
  fact almost word for word (word overlap of 0.8 or more): the CV was transplanted, not written.
- Fail when `content.language` differs from the ad's language, and when a letter names an employer
  that only appears in an example letter (Knowit, Ravion) and is not the target employer.
- Warn (rewrite once, never block approval) on: a number in a bullet or letter paragraph that the
  cited facts do not contain, a skill in the skills section that is in neither the skill table nor
  the selected facts, a bullet citing a fact of another entry, and a CV or letter over its page
  limit. Calibration on 26 Oct 2026: the style rules found nothing in the ten documents that were
  sent; the skills check flags descriptive phrases in them, which is why it only warns.
- On failure, call `rewriteForViolations` once with the report; if it still fails, show the
  document with highlighted violations for manual editing. Never auto-approve: `approve`
  re-validates the stored content and answers 409 with the report while any FAIL remains.

### 8.4 Banned or discouraged phrasing (configurable list, case-insensitive)

en: "passionate about", "I am excited to", "thrilled", "dynamic", "synergy", "leverage",
"spearheaded", "proven track record", "results-driven", "fast-paced environment",
"not only ... but also", "in today's", "delve", "tapestry", "I believe I would be a great fit".
sv: "brinner för", "passionerad", "drivs av", "inte bara ... utan också", "i dagens",
"dynamisk", "resultatinriktad", "jag är övertygad om att".

### 8.5 PDF rendering

- Thymeleaf HTML templates `cv.html` and `letter.html` with one CSS file, A4, rendered by
  openhtmltopdf with the Inter font embedded (bundle the TTF files; OFL licence).
- CV look: name in caps (20 pt, bold, letter-spacing), headline in navy `#1F3A5F`, contact line in
  grey, section headings uppercase with a thin rule, dates right-aligned on each role line,
  9.5 pt body, maximum 2 pages (warn if it exceeds 2).
- Letter: same header, subject line bold, subline grey, 11 pt body with 1.5 line height,
  1 page (warn if longer).
- File names: `CV_Cai_Birch_<Employer>.pdf` and `Personligt_brev_Cai_Birch_<Employer>.pdf`
  (or `Cover_letter_...` for English), ASCII-safe.
- Documents are editable in the UI (paragraph-level text areas) before approval. Re-render on
  save and bump the version. There is one current document per (job, kind); a save or a
  regeneration replaces it, bumps `version` and clears `approved`. The job becomes DOCS_READY when
  both documents are approved. Generation runs as a `DOCUMENTS` pipeline run (UI polls
  `/api/runs/{id}`) and uses the search profile of the run the ad came from.
- Example letters (`resources/claude/examples`): the Knowit letter for Swedish ads, the Ravion
  letter for English ads. They show shape only.

### 8.6 Ask Claude (document chat)

`POST /documents/{id}/chat {message, content?, history?}` revises the CV or letter as the user asks. The model sees the draft
on screen, the ad, the writing rules and every fact of every entry the user has not excluded for this job, and must cite only
those. Java then validates the answer against the writeCv/writeLetter schema, passes a CV through `CvAssembler` (database-owned
fields cannot change), runs the validator with one automatic rewrite, adds entries the revision now uses to the job's selection,
renders and stores a new version. Approval is revoked, as for any edit; the page says so and offers an undo (the previous content
is saved back). A request that needs something not in the CV database changes nothing and says what is missing.

## 9. Applying in the browser

### 9.1 Browser

- Playwright for Java, `launchPersistentContext(~/.jobpilot/browser-profile, headless=false)`,
  Chromium. The same profile keeps the user's logins (Varbi, Jobshark, ledigajobb,
  Arbetsförmedlingen with BankID) between sessions.
- **Out of sight until needed (ADR 0018).** The window starts minimised (`--start-minimized`, then DevTools
  `Browser.setWindowBounds windowState=minimized`) with `--disable-backgrounding-occluded-windows`,
  `--disable-renderer-backgrounding`, `--disable-background-timer-throttling` so hidden pages run at full speed, and through XWayland
  (`--ozone-platform=x11`) on a Wayland session. It is restored and the tab brought to the front only when a sign-in is genuinely
  needed, the form is filled and ready for review, or the person pressed "Open the browser to take over" (which keeps it up until
  that tab is closed); it is minimised again when no tab needs the person. `jobpilot.browser.background=false` keeps it always visible.
  Moving the window off-screen was tried and rejected: window managers keep it on screen. One window holds every tab, so showing it
  shows all of them.
- One tab per job. Keep a map of job id to page so the UI can show per-tab status. A tab that the application's own Apply click
  opened in a new window replaces the job's tab.
- Accept only necessary cookies when a cookie banner is detected (button text matches
  "nödvändiga", "necessary", "reject", "endast").

### 9.2 Filling a page

1. Navigate to `apply_url` (fall back to `url` when `apply_url` is not an address), with one retry. Stage: *Opening job ad*.
2. **Look, decide, act, repeat** (ADR 0023; `ApplyService.browse`). Each look waits for the page to settle (load, a short network
   lull, then three equal accessibility snapshots in a row, so a button drawn late by a single-page app is seen), dismisses a
   necessary-only cookie notice, and takes `page.ariaSnapshot` in AI mode: every element with a role, a name and a `ref`
   (`PageSnapshot`). The snapshot sent to the model never carries what a text box holds (`[filled]` replaces it), so a typed password,
   a personal number or anything else the person typed cannot reach a prompt. The model (`browseApplication`) answers with the kind of
   page (ad, form, login, review, confirmation, error) and the actions for this page, by ref. The program validates and performs
   them, then looks again. Limits: 30 looks, `find-form-seconds` (240) to reach a form, two looks in a row that change nothing end the
   run, and a button is never pressed twice on the same page.
3. **Wall rule.** A tab is `WAITING_FOR_SIGN_IN` (window revealed, polled every 3 s) only when a password field is visible or a
   password is being confirmed and the page offers no guest route ("Apply manually", "Continue as guest", "Apply without account"),
   or when the model calls the page a login page, nothing else could be done, and the main content (header, nav and footer are
   ignored) has sign-in, BankID or account wording. A "Logga in" link in a header, an ad that says "registerkontroll", or a form that
   offers BankID never counts. When no way on is found the tab says why (`NEEDS_MANUAL_APPLY`), offers "Open the browser to take
   over", and carries on by itself when the form shows (poll).
4. **Fields.** The validator sees the snapshot's controls as `FormField`s (ref as id; file inputs keep `data-jp-id` from the extract
   script because the snapshot does not list them). Actions: `fill`, `choose` (a native select by label, or a custom drop-down:
   open it, click the option whose text matches; only an option that exists is picked), `check`, `uncheck`, `upload`, `click`.
5. **Clicks.** One per look, last, through `BrowserGuard.follow`: the Apply link of an ad (rules unchanged, ADR 0018) or a Next /
   Continue / Nästa / Fortsätt / Apply manually / Continue as guest button whose whole label is such a word (`ADVANCE_LABEL`). A Next
   is refused on a page whose visible heading or step name reads review, summary, confirm or granska, and when any other visible
   control says submit, send, finish, register or a bare Ansök/Apply.
6. Re-read after the actions (snapshot for refs, extract script for `jp-` ids) and show a per-tab summary: filled, skipped (with
   reasons), and questions for the user. Screening questions that need judgment ("Har du erfarenhet av Angular?") are filled only
   if the answers memory or CV database clearly supports an answer. Otherwise they go to `questionsForUser`, the run stops there
   (`NEEDS_ANSWERS`), and the user's answer is saved to the answers memory; Fill this page continues from where the form is.
7. The run ends `READY_FOR_USER` at a review page, or when the model has nothing left to do on a form that was filled. The user
   checks the page and presses the final button.
8. **Stages and failures.** The card shows *Opening job ad, Finding application form, Filling personal details, Uploading CV and cover
   letter, Answering questions, Ready for your review* as a progress bar (`stage` in `GET /api/apply/status`). A step that throws, or
   a form not found in time, ends in `ERROR` with `failedStage` and `failure`; the card names the stage and offers "Try again" and
   "Open the browser to take over". The model call for the form is repeated once.

Nothing in the model's answer is trusted (`ActionValidator`). An action runs only if its `fieldId` is
one of the fields just extracted, it fits the field type (fill only text and textarea, check only
checkbox and radio, select only an existing option, upload only a file input), the field is not a
password, hidden, credential or consent field, and the value can be traced: name, email, phone and
city fields must equal the profile exactly, `source: memory` must equal the stored answer for the
normalised question (for a radio, the option that matches it), `upload` uses only an approved
document of that job (an unapproved one is skipped with the reason), and a field that already holds
a different value is left alone. The Ja and Nej buttons of a radio group are one question. After
uploads the page is read again; fields that appeared (Ponty's category selects) get one more mapping
pass, never more. Each pass is stored in `application_form_snapshot`. A job becomes FILLED after a
fill that left no questions, never APPLIED.

### 9.2.1 Batches and queueing

`POST /apply/batch` ("Fill all") accepts any number of jobs. At most `browser.max-tabs` (5) tabs are open at once; the rest are
QUEUED and start by themselves, in order, when a tab is closed or its application is confirmed as submitted. Form filling has its
own two-thread executor, so a long documents batch or discovery run cannot starve it. A tab the user closed by hand is
reconciled to CLOSED (Focus gives a message, Close is idempotent, "Restart and refill" reopens it). Jobs whose link is on the
paste-only list get status APPLY_MANUALLY and no tab.

### 9.5 Saved portal logins (ADR 0016)

When a portal needs a sign-in, the tab waits (`NEEDS_LOGIN`) and the user signs in or signs up themselves; JobPilot never creates
accounts and never touches BankID. Afterwards it asks whether to remember that login. The user types the username and password into
JobPilot's own dialog (never read from the portal's field); the password goes to the OS credential store under the portal's host,
the host and username to `saved_login`. Later, when a tab waits at a login on exactly that host, JobPilot's own code
(`BrowserGuard.fillSavedLogin`, never a model-proposed action, never in a prompt) types the username and password into a login
form with one password field and at most three inputs, and nothing else: it does not press Log in (rule 1) and refuses on any
other host or on a sign-up form (a second password field, or more than three inputs); a BankID-only page has no password field and is skipped by the same rule. Saved logins are listed and deleted in Settings.

### 9.3 Guardrails enforced in code

- `BrowserGuard` is the single choke point for every action on an external page. `ActionExecutor`
  refuses any action on: `input[type=password]`, buttons or links, and checkboxes and radios (ticking
  and unticking) whose label, aria-label, title, legend or nearby text matches
  `/samtyck|consent|gdpr|villkor|terms|privacy|integritet|talent|stay in touch|nyhetsbrev|newsletter|godkänn|accept|personuppgift|policy|intyga|marknadsför|marketing|prenumer|subscribe/i`
  (the last eight alternatives were added in milestone 5 to make the rule stricter).
- Only `BrowserGuard` may call `click()`, and only for a named purpose (`COOKIE_NECESSARY`, `AUTOCOMPLETE_OPTION`,
  `AKTIVITET_SPARA`, `OPEN_APPLICATION_FORM`, `ADVANCE_STEP`, `OPEN_DROPDOWN`). Enter is never pressed. `evaluate` runs only the fixed scripts in `PageScripts`
  (extract, detect, read-saved-activities); `page.ariaSnapshot` is read-only and lives in `PageScripts`. A test scans `src/main/java` and fails the build if any
  other class calls a raw click, key press, `evaluate`, `dispatchEvent`, form submit, `navigate` or popup listener.
- No code path calls `click()` on elements matching
  `/skicka|send|submit|apply now|ansök|slutför|bekräfta|confirm|finish|registrera|register|spara ansökan/i`, and none on a
  `type=submit` control or a `<button>` without a type inside a `<form>` (a default submit). The one purpose that may carry
  "Ansök" or "Apply" is `OPEN_APPLICATION_FORM` (the label must start with an apply word, such as Ansök, Apply, Jag är
  intresserad, I'm interested), and it is refused when the page already is a form, when JobPilot has filled anything on the
  application, inside a form that has inputs, or for any `FINAL_SUBMIT` word. A link with an address is loaded instead of
  clicked, after checking http(s) and the paste-only list. The other allowed clicks are cookie "necessary only" buttons and
  autocomplete options chosen by the executor itself.
- Unit-test these guards with fixture HTML pages.

### 9.4 Site notes from 26 Oct 2026

- **Varbi** (Arbetsförmedlingen and many agencies): requires a Varbi login (user). The draft is
  saved server-side. CV file input labelled "Ladda upp – CV"; other files under "Övriga filer".
  The "Ta bort" link may call `confirm()`, so register a dialog handler that accepts only for
  that removal. Screening questions are radio groups plus optional text areas. The GDPR checkbox
  stays for the user.
- **Workday** (Saab): "Apply Manually" works without an account. Sessions expire after
  inactivity ("Something went wrong"), so detect that and offer "Restart and refill".
  Multi-select prompt fields need type + Enter. Steps: My Information, My Experience
  (attachments, websites), Application Questions (talent community consent = user),
  Voluntary Disclosures, Review.
- **Ponty** (Friday): no account. Name, email, phone, URL; one multiple file input; each
  uploaded file needs a category select (CV, Portfolio, Personligt brev); consent checkbox = user.
- **ledigajobb.se**: requires login. CV single file input, "Andra dokument" multiple file
  input, 500-character message, skill tag buttons (toggle only the ones the CV supports).
- **Jobshark** (Comstream): requires an account. After login, an "Apply for Job" modal with a
  multiple file input; "Apply Now" submits (never click).

## 10. Aktivitetsrapport

Automation of
`https://arbetsformedlingen.se/for-arbetssokande/mina-sidor/aktivitetsrapportera/lagg-till-aktivitet`
in the JobPilot browser profile. The user must be signed in (BankID). If the page redirects to
login, set `WAITING_FOR_USER`.

Per job (verified by hand on 26 Oct 2026):
1. Section "Sökta jobb" is open by default on this page.
2. **Yrkesroll**: autocomplete text input. Type the yrkesroll (for example
   "Systemutvecklare/Prog"), wait about 1.5 s, click the listitem whose text equals
   `searchProfile.yrkesrollForAktivitetsrapport` exactly ("Systemutvecklare/Programmerare").
3. **Arbetsgivaren**: text input. Use `employer`, with the end client in brackets when the ad is
   via an agency: "Comstream AB (Taxi Stockholm)", "Friday (elektroniktest)".
4. **Välj omfattning**: radio Heltid / Deltid / Timmar vid behov (from the ad, default Heltid).
5. **Var finns jobbet?**: radio Sverige / Obestämd ort / Utomlands.
6. **Sök och välj ort**: autocomplete. Type the first 5 or 6 characters, wait about 2 s, press
   ArrowDown to open the list, click the listitem whose text equals the municipality
   ("Stockholm", "Solna", "Järfälla").
7. **Svarade du på en annons när du sökte jobbet?**: radio Ja (default for ads).
8. **Vilket datum sökte du jobbet?**: `input[type=date]`; use `locator.fill("2026-10-26")`
   (ISO), not keyboard typing, so locale does not matter.
9. Click **Spara** (the only button this module may click), then wait for the toast containing
   "Din aktivitet är sparad" and the employer name. Record the result in `aktivitet_log`.
10. The form resets after saving; repeat for the next job.

Milestone 6 decisions (see ADR 0006):
- No model is used. The form is fixed, so the seven fields are located by label or legend and mapped in Java.
  If a field is missing or ambiguous the form "looks different" and nothing is saved.
- Plan, then confirm: the UI shows all seven values per job (editable) and the server writes exactly what was
  confirmed. Only jobs with status APPLIED are eligible; the default date is the day the job was marked applied
  (`job.applied_at`). The employer label is "Employer (end client)" only when the ad names someone else.
- A job is saved only when every field was filled and read back correctly (yrkesroll, employer, ort and date as
  exact text, one radio chosen in each group). Any refusal or mismatch means that job is not saved and the next
  one is tried. Spara is clicked once per job and never retried.
- Spara is clicked only if the guard was given the configured form address, the page is exactly that address,
  exactly one visible control reads exactly "Spara", and it passes the submit-like deny list. "Skicka in" is
  refused for every purpose. The only key ever pressed is one ArrowDown to open the ort list; Enter never.
- Autocomplete lists: items with `role=option`, or list items under a `role=listbox` or under the list the input
  points at with `aria-controls` or `aria-owns`. Only visible items count and the text must match exactly.
- The confirmation toast must contain "Din aktivitet är sparad" and the employer. No toast: the result is
  UNCONFIRMED and the whole batch stops; the user checks by hand. An unreadable saved list or a different form
  also stops the batch. Remaining jobs are logged as SKIPPED with the reason.
- Duplicates: a third fixed read-only script reads the saved activities (ISO or Swedish dates). The same
  employer on the same date is a DUPLICATE: skipped, and the job stays APPLIED (it may be a second application
  there; the user logs it by hand if so). Only a job JobPilot itself saved earlier counts as LOGGED.
- LOGGED only after a confirmed save (or a job JobPilot itself saved earlier). The run is an `AKTIVITET` run; a login or BankID wall
  puts it in WAITING_FOR_USER and a read-only poll continues when the wall is gone. Only the two configured
  addresses (`jobpilot.aktivitet.form-url` and `list-url`) are ever opened. Real Arbetsförmedlingen markup is
  unverified: tests use fixtures only.

Never click anything that submits the monthly report ("Skicka in"). The report opens 1-16 of the
following month; show a reminder banner in the UI during that window.

Before logging, check the saved list on
`/for-arbetssokande/mina-sidor/aktivitetsrapportera` for an identical employer and date to avoid
duplicates.

### 10.1 Reported status (2026-10-28)

A submitted application (`submitted_application`, section 4.2) is *reported* once an aktivitet run saved it (`aktivitet_log`
`SAVED`); `reportedAt` is read from the log. The Activity report lists what still has to be reported and keeps reported ones in a
collapsed "Already reported" list; the confirmation step refuses a job that was already saved ("Already logged"), so nothing is
reported twice. The feature only exists for countries with `activityReport`.

## 11. REST API (prefix /api)

```
GET/PUT  /profile
GET/POST /cv/entries            GET/PUT/DELETE /cv/entries/{id}
POST     /cv/entries/{id}/facts  PUT/DELETE /cv/facts/{id}
GET/POST /cv/tags  GET/POST /cv/skills   DELETE /cv/skills/{id}
GET/PUT  /cv/gaps               known gaps (PUT replaces the list)
GET/PUT  /cv/answers            answers memory (PUT upserts one answer by normalised question)
POST     /cv/seed               body = seed/cv_database_seed.json; 409 unless the CV database is empty
POST     /cv/import              (multipart: PDF/DOCX/TXT/MD files) -> starts a background reading (a pipeline run of kind IMPORT): {runId, status}
GET      /cv/import/{runId}      RUNNING | DONE (with the preview) | FAILED;  GET /cv/import/current: the newest reading not yet confirmed or discarded (204 if none);  DELETE /cv/import/{runId} discards it
POST     /cv/import/{previewId}/commit   {entries: [{entry, mergeIntoEntryId|null}], skills, profile} -> {entryIds, created, enriched, facts, skills, profileFilled}
POST     /runs                   {prompt, maxAgeDays?, limit?, includeWeb?} -> run id
GET      /runs/{id}              status, step, progress, message
PUT      /runs/{id}/profile      edit the SearchProfile before search continues
POST     /runs/{id}/continue     | /cancel
GET      /runs/{id}/jobs?q=&status=&language=&minScore=&sort=rank|deadline|published&top=
GET      /runs/{id}/jobs.csv  /runs/{id}/jobs.xlsx   export of the full ranked table
GET      /jobs/{id}?run={runId}  full ad text and the score for that run's search profile
PATCH    /jobs/{id}             status, notes (pinned/excluded entries arrive with milestone 4)
POST     /jobs/{id}/documents    generate CV and letter ({runId?}) -> {runId} of a DOCUMENTS run
GET      /jobs/{id}/documents    both documents, the last selection, pinned and excluded entries
PATCH    /jobs/{id}/selection    {pinned:[entryId], excluded:[entryId]}
GET      /documents              overview of jobs that have documents
GET/PUT  /documents/{id}         edit content -> re-render
POST     /documents/{id}/approve
GET      /documents/{id}/pdf
POST     /runs                   also {mode: TARGETED|DISCOVER, aggressive?}; Discover needs no prompt
GET/PUT  /runs/{id}/roles        Discover: the suggested roles; PUT = the user's complete list (ticked, renamed, added)
POST     /import                 {url, text?} -> {id} of a MANUAL run;  POST /import/{runId}/text {text}
GET      /applications           the selected jobs with documents, generation progress, role type, mode, submission
POST     /applications           {jobIds, runId?} select;  DELETE /applications/{jobId}
POST     /applications/documents {jobIds, runId?} select and write documents as one batch (202)
POST     /applications/{jobId}/submitted   the user's confirmation that they submitted by hand;  DELETE undoes it
POST     /documents/{id}/chat    {message, content?, history?} -> {reply, changed, approvalRevoked, document}
POST     /apply/batch            {jobIds:[...]} any number; five tabs at a time, the rest queue
POST     /apply                  {jobIds:[...]} (at most 5) -> opens tabs and fills
POST     /apply/{jobId}/focus    bring the tab to the front;  POST /apply/{jobId}/close
POST     /apply/{jobId}/fill     re-scan and fill the current page (?restart=true reloads the application first)
GET      /apply/status           per-tab status and questions for the user
POST     /apply/{jobId}/answers  answers to questionsForUser (stored in answers memory)
GET      /aktivitet/plan?jobIds= what would be written for these jobs (all seven values), or why a job cannot be logged
POST     /aktivitet/log          {entries:[...]} exactly what the user confirmed -> {runId} of an AKTIVITET run
GET      /aktivitet/log          every attempt: SAVED, DUPLICATE, SKIPPED, UNCONFIRMED or FAILED, with a message
GET      /aktivitet/reminder     whether the report window (days 1 to 16) is open, from the injected clock
GET/PUT  /settings/style-rules  writing rules (PUT /settings/style-rules/{id}: ruleText, enabled)
GET/POST /settings/banned-phrases  PUT .../{id} {enabled}, DELETE .../{id}
POST     /cv/preview-selection  {ad} -> entries and facts that selectFacts would choose (nothing stored)
         (Claude CLI path, model and the other SPEC 13 values are still environment variables, not editable in the UI)
GET      /claude/health          GET /claude/usage
-- overhaul (28 Oct 2026) --
GET      /onboarding             state and first open step;  PUT /onboarding/mode {DISCOVER|PROMPT};  POST /onboarding/complete | /reopen
GET/PUT  /claude/connection...   status; /provider {id: "cli"}; /cli-model (the default model); PUT /stage-model/{SEARCH|RANKING|CV_READING|DOCUMENTS|AUTOFILL} {model}; POST /test
GET/PUT  /country                the countries (available or coming soon) and the chosen one
GET/PUT  /settings/performance   {claudeConcurrency 1..8};  GET/PUT /settings/search {careerPages}
POST     /applications/delete    {jobIds} delete completely (409 if one is submitted or being written; nothing is deleted then)
POST     /applications/approve-all   per document: approved, already approved, or refused with the reason (never all-or-nothing)
POST     /applications/apply     opens only applications with both documents approved; the rest come back as skipped with a reason
GET      /submitted[?reported=]  GET /submitted/{id}  GET /submitted/{id}/cv.pdf | letter.pdf   the frozen record of what was sent
GET/PUT  /logins                 DELETE /logins/{host}   saved portal logins (host and username only; the password never comes back)
GET      /runs/{id}              also etaSeconds while ads are scored
```

## 12. Frontend

React 19, Tailwind 4 with design tokens (CSS variables, light and dark; `<html data-theme>` always holds the resolved theme and is
set before the first paint), Public Sans and JetBrains Mono bundled with the app (no CDN: it works offline), warm paper background,
white panels with hairline borders, one deep teal accent, small radii, almost no shadow, one short entrance animation that respects
"reduce motion". Light is the default (the operating system setting is not consulted); a light/dark switch sits in the top right corner
of every screen and the choice is remembered on this computer. The shell (`Layout`) wraps every route including `/welcome`: the bar
across the top (brand, progress, theme switch), the page, and the bottom menu (CV database, Country, Claude, Submitted applications,
Extra features, Settings). Pages:

1. **Welcome** (`/welcome`): the four onboarding steps (section 2.2).
2. **Search mode** (`/`): nothing is chosen on arrival, so only the two large mode buttons show (with "add a job from a link" below);
   choosing one shrinks them to the compact size and opens the form of that mode under them (animated, none with "reduce motion"). The form of the chosen mode, "add a job from a link", recent searches, the
   activity-report reminder (only where it exists).
3. **Run search** (`/runs/{id}`): the processing view (ring, percent, what is happening, time left, cancel), results as they are
   scored (TanStack Table with selection basket and "Create tailored documents for N selected jobs"), the search-plan review for
   Prompt mode ("Approve and search"), the role picker for Discovery.
4. **Documents** (`/documents`, `/documents/job/{id}`): the overview with the two approval columns, "Approve all" and "Apply (N)";
   per job the editor and PDF preview with "Ask Claude" and a prominent Approve button.
5. **Apply** (`/apply`): a card per application with its status, login alerts, questions, "Save your login?" after a sign-in,
   and the tab controls. **Submit** (`/submit`): the applications waiting for the user's own click, "Open the application" and
   "I submitted it" with a confirmation.
6. **Submitted applications** (`/submitted`, `/submitted/{id}`), **Extra features** (`/extras`), **Activity report** (`/aktivitet`).
7. **CV database** (`/cv`) as before, and **Settings** (`/settings/{section}`: connection, country, search, performance, logins,
   writing): Claude connection and model, country, search, speed and usage, saved logins, writing rules. No Appearance section (the
   switch is in the top bar) and no "run setup again" (the steps are in the menu).
8. **Not started** (`/not-started/{stage}`): shown for a pipeline stage whose work has not been done.

Notifications: when an application becomes ready for submission the app shows a message and, if the user allowed it, a system
notification (Web Notifications; asked for at the first Apply). The app-wide watcher runs in the shell.

## 13. Settings defaults

```
claude.path=claude
claude.model=            (empty = Claude Code default)
claude.timeoutSeconds=180
claude.webTimeoutSeconds=600
claude.concurrency=4
data.dir=~/.jobpilot
search.defaultMaxAgeDays=7
search.defaultLimit=20          (at most 150)
results.topN=10
(no default yrkesroll: the search plan's occupation, else the ad's own title)
```

## 14. Testing

- Unit: SearchProfile mapping, JobTech mapping (recorded JSON fixtures), dedupe, pre-filters,
  score totals, validators (dashes, new-grad phrases, citations, banned phrases), template
  rendering, guardrail regexes, form-action executor against fixture HTML (Playwright, local
  file URLs).
- Integration: Testcontainers PostgreSQL for repositories and Flyway; a full pipeline test with
  `FakeClaudeRunner` and a WireMock JobTech server.
- Frontend: Vitest + React Testing Library for the table (sort, filter, status changes), the
  documents editor and the CV database editor.
- Manual acceptance (Section 15) against real sites, run by the user.

## 15. Milestones and acceptance criteria

1. **Skeleton**: compose file, Spring Boot app, Flyway V1, React shell with routing, Claude
   health check working against the real CLI. Done when `/api/claude/health` returns ok.
2. **CV database**: CRUD and UI, seed import from `seed/cv_database_seed.json`, import wizard
   from PDF via `importCv`. Done when the user's CV is fully represented and editable.
3. **Search and ranking**: SearchProfile, JobTech client, optional web discovery, scoring, and
   results table with drawer and export. Done when a run for "new-grad Java Stockholm 7 days"
   returns a ranked table comparable to the 26 Oct 2026 manual result.
4. **Documents**: fact selection, CV and letter generation, validators, PDF rendering, editor,
   approval. Done when generated documents for a Swedish and an English ad pass validation and
   look like the 26 Oct PDFs.
5. **Apply**: persistent browser, tabs, field extraction, mapping, guardrails, monitor UI.
   Done when Ponty and Workday forms fill end to end up to (not including) submit, and a Varbi
   form fills after the user signs in.
6. **Aktivitetsrapport**: logging flow with de-duplication. Done when five jobs save correctly
   in one go.
7. **Role switch check**: run "PE teacher, Nacka" and confirm that the search terms, selected
   facts (GIH, teaching, Advance HE), document tone and yrkesroll all change.

## 16. Security and privacy

- Local only; bind 127.0.0.1; CORS limited to the UI origin.
- Personal data stays on the machine. The only outbound calls are JobTech, sites the user
  applies to, and the Claude Code CLI.
- Secrets live in the OS credential store. Saved portal passwords live only there (without a store nothing is saved). The browser profile holds cookies; document this in the README.
- Back up the database with `pg_dump` from a script in `scripts/backup.sh`.

## 13. CV import, merging and per-stage models (ADR 0021, 0022)

- **Import is a background run.** Uploading extracts the text at once (a bad file is refused immediately) and reads it as a `pipeline_run`
  of kind `IMPORT` on the pipeline executor. The result waits in memory for an hour as a preview. The UI polls `/cv/import/current`, so a
  reading carries on when the user leaves the page, shows in the top bar ("Reading your CV"), is announced when finished, and is found
  again on return. Setup uses the same endpoints.
- **Entries are merged, never duplicated.** A found entry that matches a stored one (same kind; same organisation and a similar title or
  the same start period; or, with no organisation, a similar title) is added to it: facts not already there (80 % word overlap in the same
  language, or a longer fact containing every word of a shorter one, counts as there; rewordings and facts in the other language are settled by one batched `matchFacts` Claude call during the reading, stage "Reading your CV", and if it fails the word match stands) are appended, blank fields are filled, tags are added. Existing facts, titles and anything the user wrote are
  never edited or removed, because documents cite fact ids. Two roles at one employer with different titles and dates stay two entries.
  Two files describing one job are folded together before comparing with the database. The preview says "Adds N facts to <entry>" and
  offers "Add as a new entry instead"; the commit carries `mergeIntoEntryId` per entry and does not re-match.
- **Models per stage.** Search (plans, role ideas, pasted ads, web discovery), Scoring, Reading your CV, CV and cover letter (selection,
  writing, revising, rewriting), Auto-fill. A stage without a choice uses the default model. Every prompt template must belong to a stage
  (a test enforces it).
- **Help.** `/help` (offline) with the five stages, what JobPilot never does, and common questions; a six-step tour shown once after
  setup and reopenable from Help (a dialog: Escape closes, focus returns, reduced motion respected).
