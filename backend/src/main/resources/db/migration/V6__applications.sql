-- Stage 2 to 4 of the guided flow: the jobs the user selected for tailored documents and applications.

-- How a run found its jobs: a targeted prompt, discovered role types, or a pasted job URL.
ALTER TABLE pipeline_run ADD COLUMN mode TEXT NOT NULL DEFAULT 'TARGETED'
    CHECK (mode IN ('TARGETED', 'DISCOVER', 'MANUAL'));

-- One row per selected job: the pipeline basket that Documents and Finalize Applications read. submitted_at is set only by
-- the user's own "I submitted it" click (the app never submits), and docs_json records which documents were used then.
CREATE TABLE application (
    job_id       BIGINT PRIMARY KEY REFERENCES job (id) ON DELETE CASCADE,
    run_id       BIGINT REFERENCES pipeline_run (id) ON DELETE SET NULL,
    search_mode  TEXT NOT NULL DEFAULT 'TARGETED' CHECK (search_mode IN ('TARGETED', 'DISCOVER', 'MANUAL')),
    role_type    TEXT,
    selected_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at TIMESTAMPTZ,
    docs_json    JSONB
);
CREATE INDEX idx_application_submitted ON application (submitted_at);
