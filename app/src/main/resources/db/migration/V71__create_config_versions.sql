-- Configuration used by each scheduled job run and report (#426).
-- Each distinct execution configuration of a definition is stored once, keyed by its
-- SHA-256 content hash; runs and reports point at the version they ran with. Versions belong
-- to their definition and are deleted with it. Snapshots never contain secret values.
CREATE TABLE scheduled_job_version (
    id BIGINT PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES scheduled_job(id) ON DELETE CASCADE,
    config_hash VARCHAR(64) NOT NULL,
    config_snapshot TEXT NOT NULL,
    created_on TIMESTAMP NOT NULL,
    CONSTRAINT uq_sjv_job_hash UNIQUE (job_id, config_hash)
);

CREATE SEQUENCE IF NOT EXISTS scheduled_job_version_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE report_definition_version (
    id BIGINT PRIMARY KEY,
    definition_id BIGINT NOT NULL REFERENCES report_definition(id) ON DELETE CASCADE,
    config_hash VARCHAR(64) NOT NULL,
    config_snapshot TEXT NOT NULL,
    created_on TIMESTAMP NOT NULL,
    CONSTRAINT uq_rdv_def_hash UNIQUE (definition_id, config_hash)
);

CREATE SEQUENCE IF NOT EXISTS report_definition_version_SEQ START WITH 1 INCREMENT BY 50;

-- Null for runs and reports created before this migration (no snapshot was recorded).
ALTER TABLE scheduled_job_run ADD COLUMN config_version_id BIGINT
    REFERENCES scheduled_job_version(id) ON DELETE SET NULL;
ALTER TABLE report ADD COLUMN config_version_id BIGINT
    REFERENCES report_definition_version(id) ON DELETE SET NULL;

CREATE INDEX idx_sjr_config_version ON scheduled_job_run(config_version_id);
CREATE INDEX idx_report_config_version ON report(config_version_id);
