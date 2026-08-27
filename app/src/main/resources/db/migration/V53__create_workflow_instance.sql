CREATE TABLE workflow_instance (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL UNIQUE REFERENCES project(id),
    definition_id BIGINT NOT NULL REFERENCES workflow_definition(id),
    definition_version INT NOT NULL,
    instance_state TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    current_node_id VARCHAR(255),
    failure_reason TEXT,
    started_on TIMESTAMP NOT NULL,
    completed_on TIMESTAMP
);

CREATE SEQUENCE IF NOT EXISTS workflow_instance_SEQ START WITH 1 INCREMENT BY 50;

CREATE INDEX idx_wf_instance_status ON workflow_instance(status);

ALTER TABLE task ADD COLUMN workflow_instance_id BIGINT
    REFERENCES workflow_instance(id);
