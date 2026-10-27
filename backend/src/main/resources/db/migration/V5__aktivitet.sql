-- Milestone 6: aktivitetsrapport.

ALTER TABLE pipeline_run DROP CONSTRAINT pipeline_run_kind_check;
ALTER TABLE pipeline_run ADD CONSTRAINT pipeline_run_kind_check CHECK (kind IN ('SEARCH', 'DOCUMENTS', 'AKTIVITET'));

-- The day the user marked the job as applied: the default "Vilket datum sökte du jobbet?" (editable before saving).
ALTER TABLE job ADD COLUMN applied_at TIMESTAMPTZ;
UPDATE job SET applied_at = created_at WHERE status IN ('APPLIED', 'LOGGED') AND applied_at IS NULL;

-- Which run and which form values a log row belongs to, plus the form's omfattning and location choice.
ALTER TABLE aktivitet_log
    ADD COLUMN run_id          BIGINT REFERENCES pipeline_run (id) ON DELETE SET NULL,
    ADD COLUMN var_finns       TEXT,
    ADD COLUMN created_at      TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX idx_aktivitet_log_job ON aktivitet_log (job_id);

-- M3 stored the workplace as "end client" whenever it differed from the employer name, which gave labels
-- such as "SAAB Aktiebolag (Saab)". These are the same employer or the city, not a client: clear them.
UPDATE job SET client_employer = btrim(client_employer) WHERE client_employer IS NOT NULL;
UPDATE job SET client_employer = NULL
WHERE client_employer IS NOT NULL
  AND (client_employer = ''
       OR lower(employer) LIKE '%' || lower(client_employer) || '%'
       OR lower(client_employer) LIKE '%' || lower(employer) || '%'
       OR lower(client_employer) = lower(coalesce(municipality, '')));
