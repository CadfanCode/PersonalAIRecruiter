-- Adding a job by its link: the pasted address stays with the run, also while the run waits for the ad text.
ALTER TABLE pipeline_run ADD COLUMN source_url TEXT;
