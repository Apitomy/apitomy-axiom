-- Rename the single-instance-per-project table into a run-history table.
--
-- H2 auto-commits each DDL statement and does not roll back a migration that fails
-- partway, so every statement below is written to be idempotent: re-running the
-- script converges the schema to the target state. (The first revision of this
-- script failed on an H2-unsupported "ALTER SEQUENCE ... RENAME TO" below, leaving
-- databases half-migrated; making the whole script re-runnable lets them recover.)
ALTER TABLE IF EXISTS workflow_instance RENAME TO workflow_run;

-- Many runs per project: remove the uniqueness of project_id while keeping the
-- foreign key. V53 declared "project_id ... UNIQUE REFERENCES project(id)" inline.
-- On H2 the foreign key adopts the unique constraint's backing index, so the
-- uniqueness can only be removed by dropping the FK, then the unique constraint
-- (which frees its index), then re-adding the FK. The engine generates the
-- constraint names, so they are looked up and the statements built dynamically;
-- the COALESCE / CASE no-ops keep each step safe to re-run.
EXECUTE IMMEDIATE (
    SELECT COALESCE(MAX('ALTER TABLE workflow_run DROP CONSTRAINT ' || tc.constraint_name), 'SET @noop = 0')
    FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name
    WHERE tc.table_name = 'WORKFLOW_RUN'
      AND tc.constraint_type = 'FOREIGN KEY'
      AND kcu.column_name = 'PROJECT_ID'
);
EXECUTE IMMEDIATE (
    SELECT COALESCE(MAX('ALTER TABLE workflow_run DROP CONSTRAINT ' || constraint_name), 'SET @noop = 0')
    FROM information_schema.table_constraints
    WHERE table_name = 'WORKFLOW_RUN' AND constraint_type = 'UNIQUE'
);
EXECUTE IMMEDIATE (
    SELECT CASE WHEN COUNT(*) = 0
        THEN 'ALTER TABLE workflow_run ADD CONSTRAINT fk_wf_run_project FOREIGN KEY (project_id) REFERENCES project(id)'
        ELSE 'SET @noop = 0' END
    FROM information_schema.table_constraints
    WHERE table_name = 'WORKFLOW_RUN'
      AND constraint_type = 'FOREIGN KEY'
      AND constraint_name = 'FK_WF_RUN_PROJECT'
);

-- Link a run to its execution trace (nullable; trace creation is best-effort).
ALTER TABLE workflow_run ADD COLUMN IF NOT EXISTS trace_id UUID;

-- Rename the Hibernate id sequence to match the new table name. H2 does not
-- support "ALTER SEQUENCE ... RENAME TO", so drop the old sequence and recreate it
-- under the new name, seeded past the highest existing id to avoid PK collisions.
DROP SEQUENCE IF EXISTS workflow_instance_SEQ;
CREATE SEQUENCE IF NOT EXISTS workflow_run_SEQ
    START WITH (SELECT COALESCE(MAX(id), 0) + 50 FROM workflow_run) INCREMENT BY 50;

-- Replace the status-only index with ones that support latest-run and per-project
-- history queries.
DROP INDEX IF EXISTS idx_wf_instance_status;
CREATE INDEX IF NOT EXISTS idx_wf_run_project_started ON workflow_run(project_id, started_on DESC);
CREATE INDEX IF NOT EXISTS idx_wf_run_status ON workflow_run(status);

-- Task -> run linkage rename, plus the node this task represents.
ALTER TABLE task ALTER COLUMN IF EXISTS workflow_instance_id RENAME TO workflow_run_id;
ALTER TABLE task ADD COLUMN IF NOT EXISTS node_id VARCHAR(255);
