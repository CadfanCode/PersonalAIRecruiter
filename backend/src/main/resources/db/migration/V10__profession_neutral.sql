-- JobPilot is for any profession (overhaul, ADR 0013).

-- Security vetting (säkerhetsprövning) is no longer part of the profile. The column held user-entered text only.
ALTER TABLE profile DROP COLUMN security_vetting;

-- The first scoring axis was called "tech"; it measures how well the candidate's skills cover the ad, in any field.
ALTER TABLE job_score RENAME COLUMN tech TO skills;

-- CV entries for professions beyond tech: licences (nursing, trades, driving) and courses.
DO $$
DECLARE c text;
BEGIN
    SELECT conname INTO c FROM pg_constraint
     WHERE conrelid = 'cv_entry'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) ILIKE '%kind%';
    IF c IS NOT NULL THEN
        EXECUTE format('ALTER TABLE cv_entry DROP CONSTRAINT %I', c);
    END IF;
END $$;
ALTER TABLE cv_entry ADD CONSTRAINT cv_entry_kind_check CHECK (kind IN ('EDUCATION', 'WORK', 'INTERNSHIP', 'PROJECT', 'OWN_BUSINESS',
    'VOLUNTEER', 'CERTIFICATION', 'LICENCE', 'COURSE', 'AWARD', 'PUBLICATION', 'CONFERENCE', 'LANGUAGE', 'OTHER'));

-- Discover roles: the middle-of-the-road group is "outside your main field", whatever that field is.
DO $$
DECLARE c text;
BEGIN
    SELECT conname INTO c FROM pg_constraint
     WHERE conrelid = 'role_suggestion'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) ILIKE '%OUTSIDE_TECH%';
    IF c IS NOT NULL THEN
        EXECUTE format('ALTER TABLE role_suggestion DROP CONSTRAINT %I', c);
    END IF;
END $$;
UPDATE role_suggestion SET category = 'OUTSIDE_FIELD' WHERE category = 'OUTSIDE_TECH';
ALTER TABLE role_suggestion ADD CONSTRAINT role_suggestion_category_check
    CHECK (category IN ('RELATED', 'HYBRID', 'OUTSIDE_FIELD', 'CUSTOM'));
