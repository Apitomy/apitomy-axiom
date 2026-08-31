-- Workflow-enablement flag: opt-in eligibility as a Flow workflow node.
ALTER TABLE action_type ADD COLUMN IF NOT EXISTS workflow_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Declared inputs (ordered).
CREATE TABLE IF NOT EXISTS action_type_input (
    action_type_id BIGINT NOT NULL,
    ordinal INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT FALSE,
    description TEXT,
    PRIMARY KEY (action_type_id, ordinal),
    FOREIGN KEY (action_type_id) REFERENCES action_type(id) ON DELETE CASCADE
);

-- Declared outputs (ordered).
CREATE TABLE IF NOT EXISTS action_type_output (
    action_type_id BIGINT NOT NULL,
    ordinal INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT FALSE,
    description TEXT,
    PRIMARY KEY (action_type_id, ordinal),
    FOREIGN KEY (action_type_id) REFERENCES action_type(id) ON DELETE CASCADE
);

-- Retire the unenforced free-text input schema.
ALTER TABLE action_type DROP COLUMN IF EXISTS input_schema;
