CREATE TABLE workflow_definition (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    content TEXT,
    current_version INT,
    created_on TIMESTAMP NOT NULL,
    updated_on TIMESTAMP NOT NULL
);

CREATE TABLE workflow_definition_version (
    id BIGSERIAL PRIMARY KEY,
    definition_id BIGINT NOT NULL REFERENCES workflow_definition(id) ON DELETE CASCADE,
    version INT NOT NULL,
    content TEXT NOT NULL,
    created_on TIMESTAMP NOT NULL,
    CONSTRAINT uq_definition_version UNIQUE (definition_id, version)
);

CREATE INDEX idx_wf_def_version_def_id ON workflow_definition_version(definition_id);
