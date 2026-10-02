-- Links between stream events and workflow runs (#420, #421).
-- No FKs to stream_event or event_processing_ledger: event retention deletes those rows, and the
-- run keeps the IDs as a historical reference.

-- The event (and ledger entry) whose create-workflow routing started the run. Null for runs
-- started any other way (e.g. manually).
ALTER TABLE workflow_run ADD COLUMN trigger_event_id UUID;
ALTER TABLE workflow_run ADD COLUMN trigger_ledger_id BIGINT;

-- One row per receive-event step of a run that a stream event resumed. The
-- workflow_event_subscription row is deleted when it is consumed, so this table is the record of
-- which event resumed the run at which node.
CREATE TABLE workflow_run_resume (
    id BIGINT PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES workflow_run(id) ON DELETE CASCADE,
    node_id VARCHAR(255) NOT NULL,
    event_id UUID NOT NULL,
    ledger_id BIGINT,
    trace_node_id BIGINT,
    resumed_on TIMESTAMP NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS workflow_run_resume_SEQ START WITH 1 INCREMENT BY 50;

CREATE INDEX idx_wf_run_resume_run ON workflow_run_resume(run_id);
CREATE INDEX idx_wf_run_resume_event ON workflow_run_resume(event_id);
