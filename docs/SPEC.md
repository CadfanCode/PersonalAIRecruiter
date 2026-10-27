# JobPilot specification

Version 1.0, 26 October 2026. Owner: Cai Birch.

## 1. Goal

A local web app that turns one starting prompt ("new-grad Java roles in Stockholm posted in the
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

## 3. Architecture

```
React UI (5173) --REST/JSON--> Spring Boot API (8080) --JDBC--> PostgreSQL
                                   |
                                   +-- ClaudeRunner ---- ProcessBuilder ---> local `claude -p` CLI
                                   +-- JobTechClient --- HTTPS ------------> jobsearch.api.jobtechdev.se
                                   +-- BrowserService -- Playwright -------> headed Chromium (persistent profile)
                                   +-- PdfRenderer ----- openhtmltopdf ----> ~/.jobpilot/documents/*.pdf
```

Single process and single user. Bind to 127.0.0.1 only. No authentication on the local API, but
reject requests whose `Origin` is not `http://localhost:5173` or `http://127.0.0.1:5173`
(CSRF protection for a localhost app).

Data directory: `~/.jobpilot/` (configurable) containing `documents/`, `browser-profile/` and
`logs/`.

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
--   profile: phone_international, work_authorisation, security_vetting
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

## 5. Claude integration

### 5.1 ClaudeRunner

All AI work goes through one class that shells out to the user's local Claude Code:

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
  180 s, web discovery and CV import 600 s), and max concurrency (default 2, a `Semaphore`).
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
| `mapFormFields` | extracted form fields, profile, job, available files, answers memory | FormActions |

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

## 7. Ranking

1. Pre-filter in Java: drop ads older than maxAgeDays, past deadline, matching exclusions, or
   outside locations (unless remote is allowed). Exclusions are whole-word, case-insensitive
   matches against the ad title and employer name only (matching the description would drop
   almost every ad that mentions "senior colleagues"). Location compares the ad's municipality
   with the profile's municipality list; an ad without a municipality is kept.
2. Build a compact CV digest (entries, facts and skills as JSON, at most around 8k tokens) once
   per run.
3. Call `scoreJob` per remaining ad (concurrency 2). Cache by (job id, CV database version,
   SearchProfile id). The run option `limit` (default 50) is the number of ads scored, best JobTech
   relevance first, unverified web dates last; other ads that passed the filters are stored and
   listed unscored. Measured 26 Oct 2026 on Sonnet: about $0.04 per scored ad.
4. Default results view: top 10 by total, with a toggle to show all.

## 8. Documents

### 8.1 Fact selection

`selectFacts` chooses entries and facts by relevance to the ad and the SearchProfile's
`relevantTags`. Rules:
- Always include contact details, all education entries (unless the user excluded one), and
  languages. Education facts are the ones Claude picked; an education entry Claude left out is
  added without bullets. Entries marked "letter" only (for example leisure facts) stay out of the CV.
- Prefer recent and relevant entries; omit unrelated ones. A teaching CV drops Spring Boot; a
  developer CV keeps teaching only as a short line that shows communication skills.
- Keep employment gaps honest: an omitted job can be summarised as one line ("Other employment
  2019-2021, available on request") if omitting it would create a gap of more than 6 months.
  Java builds the line from database dates (`GapNote`); separate periods are listed separately
  ("2018-2021 and 2025-2026"), never merged into one range.
- The user can pin or exclude entries per job in the UI before generation.

### 8.2 Generation

`writeCv` and `writeLetter` receive only the selected facts. Language = the ad's language. The
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

## 9. Applying in the browser

### 9.1 Browser

- Playwright for Java, `launchPersistentContext(~/.jobpilot/browser-profile, headless=false)`,
  Chromium. The same profile keeps the user's logins (Varbi, Jobshark, ledigajobb,
  Arbetsförmedlingen with BankID) between sessions.
- One tab per job. Keep a map of job id to page so the UI can show per-tab status.
- Accept only necessary cookies when a cookie banner is detected (button text matches
  "nödvändiga", "necessary", "reject", "endast").

### 9.2 Filling a page

1. Navigate to `apply_url` (fall back to `url`).
2. Detect login walls (password inputs, "Logga in", "Sign in", BankID, account-creation pages).
   Set the tab status to `WAITING_FOR_USER` and poll every 3 s until the page changes.
3. Extract fields with a script injected via `page.evaluate`: for every visible input, select,
   textarea and file input, assign `data-jp-id`, then collect label text (label[for], aria-label,
   closest label, preceding text), name, type, required, options, current value, and the
   fieldset or question text.
4. Call `mapFormFields` with the fields, the profile, the job, the approved documents, and the
   answers memory (previous answers to similar screening questions, keyed by normalised question
   text).
5. Execute actions with Playwright locators on `[data-jp-id=...]`: `fill`, `selectOption`,
   `check`, and `setInputFiles` for uploads (cv or letter PDF).
6. Re-extract and show a per-tab summary: filled, skipped (with reasons), and questions for the
   user. Screening questions that need judgment ("Har du erfarenhet av Angular?") are filled only
   if the answers memory or CV database clearly supports an answer. Otherwise they go to
   `questionsForUser`, and the user's answer is saved to the answers memory.
7. Multi-step forms: the user clicks Next themselves, then presses "Fill this page" in the UI.

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

### 9.3 Guardrails enforced in code

- `BrowserGuard` is the single choke point for every action on an external page. `ActionExecutor`
  refuses any action on: `input[type=password]`, buttons or links, and checkboxes and radios (ticking
  and unticking) whose label, aria-label, title, legend or nearby text matches
  `/samtyck|consent|gdpr|villkor|terms|privacy|integritet|talent|stay in touch|nyhetsbrev|newsletter|godkänn|accept|personuppgift|policy|intyga|marknadsför|marketing|prenumer|subscribe/i`
  (the last eight alternatives were added in milestone 5 to make the rule stricter).
- Only `BrowserGuard` may call `click()`, and only for a named purpose (`COOKIE_NECESSARY` or
  `AUTOCOMPLETE_OPTION`; milestone 6 adds `AKTIVITET_SPARA`). Enter is never pressed. `evaluate` runs
  only the two fixed scripts in `PageScripts`. A test scans `src/main/java` and fails the build if any
  other class calls a raw click, key press, `evaluate`, `dispatchEvent` or form submit.
- No code path calls `click()` on elements matching
  `/skicka|send|submit|apply now|ansök|slutför|bekräfta|confirm|finish/i`. The only allowed clicks
  are cookie "necessary only" buttons and autocomplete options chosen by the executor itself.
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

Never click anything that submits the monthly report ("Skicka in"). The report opens 1-16 of the
following month; show a reminder banner in the UI during that window.

Before logging, check the saved list on
`/for-arbetssokande/mina-sidor/aktivitetsrapportera` for an identical employer and date to avoid
duplicates.

## 11. REST API (prefix /api)

```
GET/PUT  /profile
GET/POST /cv/entries            GET/PUT/DELETE /cv/entries/{id}
POST     /cv/entries/{id}/facts  PUT/DELETE /cv/facts/{id}
GET/POST /cv/tags  GET/POST /cv/skills   DELETE /cv/skills/{id}
GET/PUT  /cv/gaps               known gaps (PUT replaces the list)
GET/PUT  /cv/answers            answers memory (PUT upserts one answer by normalised question)
POST     /cv/seed               body = seed/cv_database_seed.json; 409 unless the CV database is empty
POST     /cv/import              (multipart: PDF/DOCX/TXT files) -> import preview
POST     /cv/import/{previewId}/commit
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
POST     /apply                  {jobIds:[...]} (at most 5) -> opens tabs and fills
POST     /apply/{jobId}/focus    bring the tab to the front;  POST /apply/{jobId}/close
POST     /apply/{jobId}/fill     re-scan and fill the current page (?restart=true reloads the application first)
GET      /apply/status           per-tab status and questions for the user
POST     /apply/{jobId}/answers  answers to questionsForUser (stored in answers memory)
POST     /aktivitet/log          {jobIds:[...]} -> saves activities
GET      /aktivitet/log
GET/PUT  /settings/style-rules  writing rules (PUT /settings/style-rules/{id}: ruleText, enabled)
GET/POST /settings/banned-phrases  PUT .../{id} {enabled}, DELETE .../{id}
POST     /cv/preview-selection  {ad} -> entries and facts that selectFacts would choose (nothing stored)
         (Claude CLI path, model and the other SPEC 13 values are still environment variables, not editable in the UI)
GET      /claude/health          GET /claude/usage
```

## 12. Frontend

Pages (React Router), styled with Tailwind, light and dark themes, responsive down to 1280 px
(desktop tool):

1. **Dashboard**: new-run prompt box with example chips ("New-grad Java, Stockholm, 7 days",
   "PE teacher, Nacka", "Logistics, Uppsala, part time"), recent runs, an aktivitetsrapport
   window reminder, and Claude health and usage.
2. **Run view**: step tracker (Profile -> Search -> Rank -> Done), an editable SearchProfile card
   (chips for titles, skills, tags, locations; yrkesroll field), and live progress.
3. **Results table** (TanStack Table): columns Rank, Score (stacked bar tech/exp/eligibility with
   tooltip), Title, Employer (end client), Location, Published, Deadline (red if within 3 days),
   Language flag, Experience asked, Fit, Gaps, Source, Status chip. Sorting, column filters, text
   search, saved views, row selection, and a side drawer with the full ad, score breakdown,
   apply link, notes and actions (Shortlist, Generate documents, Open application, Mark applied,
   Log in aktivitetsrapport). CSV and XLSX export.
4. **Documents**: side-by-side PDF preview and editable paragraphs, validator report, fact
   citations on hover (bullet -> source fact), Approve, Regenerate, and per-job pin/exclude of
   CV entries.
5. **Apply monitor**: one card per open tab: status (Filling, Waiting for sign-in, Needs answers,
   Ready for you to submit), filled and skipped counts, questions for the user with answer
   inputs, "Fill this page" and "Focus tab" buttons.
6. **CV database**: timeline of entries grouped by kind, an inline editor for entries and facts
   (English and Swedish text), tag and skill pickers, import wizard (upload -> preview -> commit),
   and a "Preview selection for…" tool that shows which facts would be chosen for a pasted ad.
7. **Settings**: Claude CLI path, model, timeouts, concurrency, data directory, style rules and
   banned phrases editor, default yrkesroll, JobTech API key (optional).

UI polish: Inter font, navy `#1F3A5F` accent, status colours (new = grey, shortlisted = blue,
docs ready = violet, applied = green, logged = teal, rejected = red), skeleton loaders, toasts,
keyboard shortcuts (j/k to move rows, s to shortlist, g to generate).

## 13. Settings defaults

```
claude.path=claude
claude.model=            (empty = Claude Code default)
claude.timeoutSeconds=180
claude.webTimeoutSeconds=600
claude.concurrency=2
data.dir=~/.jobpilot
search.defaultMaxAgeDays=7
search.defaultLimit=50
results.topN=10
aktivitet.defaultYrkesroll=Systemutvecklare/Programmerare
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
  applies to, and Claude Code.
- Store no passwords. The browser profile holds cookies; document this in the README.
- Back up the database with `pg_dump` from a script in `scripts/backup.sh`.
