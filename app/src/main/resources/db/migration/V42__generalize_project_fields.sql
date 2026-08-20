ALTER TABLE project RENAME COLUMN issue_ref TO project_ref;
ALTER TABLE project RENAME COLUMN issue_source TO ref_source;
ALTER TABLE project ALTER COLUMN ref_source DROP NOT NULL;
ALTER TABLE project ALTER COLUMN repository DROP NOT NULL;
