-- Milestone 3: runs, jobs and scores.

-- One ad can turn up in several runs (job.dedupe_key is unique across runs), so runs link to jobs here.
-- job.run_id keeps pointing at the run that first found the ad.
CREATE TABLE run_job (
    run_id    BIGINT NOT NULL REFERENCES pipeline_run (id) ON DELETE CASCADE,
    job_id    BIGINT NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    relevance NUMERIC,
    PRIMARY KEY (run_id, job_id)
);

-- {maxAgeDays, limit, includeWeb, autoContinue} as sent when the run was created.
ALTER TABLE pipeline_run ADD COLUMN options_json JSONB NOT NULL DEFAULT '{}';

ALTER TABLE job
    ADD COLUMN notes           TEXT,
    ADD COLUMN date_unverified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN evidence_url    TEXT;

-- Scores depend on the SearchProfile and on the CV database content, so both are part of the key
-- (SPEC 7: cache by job, CV database version and SearchProfile). search_profile rows are immutable:
-- editing a profile inserts a new row and repoints the run.
DROP TABLE job_score;
CREATE TABLE job_score (
    job_id                 BIGINT NOT NULL REFERENCES job (id) ON DELETE CASCADE,
    search_profile_id      BIGINT NOT NULL REFERENCES search_profile (id) ON DELETE CASCADE,
    cv_version             TEXT NOT NULL,
    total                  INT NOT NULL,
    tech                   INT NOT NULL,
    experience             INT NOT NULL,
    eligibility            INT NOT NULL,
    deal_breakers_json     JSONB NOT NULL DEFAULT '[]',
    fit_text               TEXT,
    gaps_text              TEXT,
    selected_fact_ids_json JSONB NOT NULL DEFAULT '[]',
    model                  TEXT,
    scored_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, search_profile_id, cv_version)
);
