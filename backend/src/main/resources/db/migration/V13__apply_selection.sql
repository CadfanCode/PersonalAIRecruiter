-- The user chooses, per application, which ones go on from Documents to the Apply stage. Only those carry on.
ALTER TABLE application ADD COLUMN sent_to_apply_at TIMESTAMPTZ;
