-- Reading a CV takes minutes, so it is a run like the others: the UI polls it and it goes on while the user is elsewhere.
ALTER TABLE pipeline_run DROP CONSTRAINT pipeline_run_kind_check;
ALTER TABLE pipeline_run ADD CONSTRAINT pipeline_run_kind_check CHECK (kind IN ('SEARCH', 'DOCUMENTS', 'AKTIVITET', 'IMPORT'));
