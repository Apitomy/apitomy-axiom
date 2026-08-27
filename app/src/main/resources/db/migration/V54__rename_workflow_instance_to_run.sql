-- Rename the single-instance-per-project table into a run-history table.
ALTER TABLE workflow_instance RENAME TO workflow_run;

-- Many runs per project: drop the uniqueness constraint on project_id.
-- V53 created it inline, so Postgres named it workflow_instance_project_id_key.
ALTER TABLE workflow_run DROP CONSTRAINT IF EXISTS workflow_instance_project_id_key;

-- Link a run to its execution trace (nullable; trace creation is best-effort).
ALTER TABLE workflow_run ADD COLUMN trace_id UUID;

-- Rename the Hibernate sequence to match the new table name.
ALTER SEQUENCE IF EXISTS workflow_instance_SEQ RENAME TO workflow_run_SEQ;

-- Replace the status-only index with one that supports latest-run and
-- per-project history queries.
DROP INDEX IF EXISTS idx_wf_instance_status;
CREATE INDEX idx_wf_run_project_started ON workflow_run(project_id, started_on DESC);
CREATE INDEX idx_wf_run_status ON workflow_run(status);

-- Task → run linkage rename, plus the node this task represents.
ALTER TABLE task RENAME COLUMN workflow_instance_id TO workflow_run_id;
ALTER TABLE task ADD COLUMN node_id VARCHAR(255);
