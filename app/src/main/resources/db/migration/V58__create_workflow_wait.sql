-- Tracks parked Wait-node branches of running workflow instances. A row exists
-- for as long as a branch is waiting out its node's duration; the poller
-- deletes the row in the same transaction as resuming the branch.
CREATE TABLE workflow_wait (
    id BIGINT PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES workflow_run(id),
    node_id VARCHAR(255) NOT NULL,
    resume_at TIMESTAMP NOT NULL,
    created_on TIMESTAMP NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS workflow_wait_SEQ START WITH 1 INCREMENT BY 50;

CREATE INDEX idx_workflow_wait_resume_at ON workflow_wait(resume_at);
CREATE INDEX idx_workflow_wait_run ON workflow_wait(run_id);
