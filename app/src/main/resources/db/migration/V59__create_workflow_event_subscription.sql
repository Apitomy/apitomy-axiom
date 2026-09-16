-- Tracks parked receive-event branches of running workflow instances. A row
-- exists while a branch waits for a matching event; the dispatcher deletes the
-- row in the same transaction as resuming the branch.
CREATE TABLE workflow_event_subscription (
    id BIGINT PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES workflow_run(id),
    node_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    project_id BIGINT NOT NULL,
    created_on TIMESTAMP NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS workflow_event_subscription_SEQ START WITH 1 INCREMENT BY 50;

CREATE INDEX idx_wf_event_sub_event_type ON workflow_event_subscription(event_type);
CREATE INDEX idx_wf_event_sub_run ON workflow_event_subscription(run_id);
