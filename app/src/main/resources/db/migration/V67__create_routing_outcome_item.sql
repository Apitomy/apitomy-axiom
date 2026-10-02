-- Every result of a routing outcome (#418). routing_outcome keeps its project_id/task_id columns
-- (first project/task) for backward compatibility; items hold the complete list.
-- Items are children of an outcome: they never affect the retry counts, which count outcomes.
-- No FKs on project/task/workflow run/trace node: those have their own retention and cleanup.
CREATE TABLE routing_outcome_item (
    id BIGINT PRIMARY KEY,
    outcome_id BIGINT NOT NULL REFERENCES routing_outcome(id) ON DELETE CASCADE,
    item_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    summary TEXT,
    error_message TEXT,
    project_id BIGINT,
    task_id BIGINT,
    workflow_run_id BIGINT,
    trace_node_id BIGINT,
    created_on TIMESTAMP NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS routing_outcome_item_SEQ START WITH 1 INCREMENT BY 50;

CREATE INDEX idx_roi_outcome ON routing_outcome_item(outcome_id);
