-- =============================================================================
-- V22: Create tracing tables for end-to-end activity tracing (Epic #36)
-- =============================================================================

-- Trace table: UUID primary key (first table to use UUID PK)
CREATE TABLE IF NOT EXISTS trace (
    trace_id UUID NOT NULL PRIMARY KEY,
    trace_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    summary VARCHAR(1024) NOT NULL,
    event_id BIGINT,
    project_id BIGINT,
    report_id BIGINT,
    started_on TIMESTAMP NOT NULL,
    completed_on TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_trace_event ON trace(event_id);
CREATE INDEX IF NOT EXISTS idx_trace_project ON trace(project_id);
CREATE INDEX IF NOT EXISTS idx_trace_report ON trace(report_id);
CREATE INDEX IF NOT EXISTS idx_trace_status ON trace(status);

-- Trace node table: individual span/step in a trace tree
CREATE TABLE IF NOT EXISTS trace_node (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id UUID NOT NULL,
    parent_node_id BIGINT,
    node_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    summary VARCHAR(1024) NOT NULL,
    started_on TIMESTAMP NOT NULL,
    completed_on TIMESTAMP,
    duration_ms BIGINT,
    entity_type VARCHAR(64),
    entity_id BIGINT,
    FOREIGN KEY (trace_id) REFERENCES trace(trace_id) ON DELETE CASCADE,
    FOREIGN KEY (parent_node_id) REFERENCES trace_node(id) ON DELETE SET NULL
);
CREATE SEQUENCE IF NOT EXISTS trace_node_SEQ START WITH 1 INCREMENT BY 50;
CREATE INDEX IF NOT EXISTS idx_trace_node_trace ON trace_node(trace_id);
CREATE INDEX IF NOT EXISTS idx_trace_node_parent ON trace_node(parent_node_id);
CREATE INDEX IF NOT EXISTS idx_trace_node_entity ON trace_node(entity_type, entity_id);

-- Tool execution table: detailed record of an MCP tool invocation
CREATE TABLE IF NOT EXISTS tool_execution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id UUID NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    tool_input TEXT,
    tool_output TEXT,
    status VARCHAR(32) NOT NULL,
    duration_ms BIGINT,
    created_on TIMESTAMP NOT NULL,
    FOREIGN KEY (trace_id) REFERENCES trace(trace_id) ON DELETE CASCADE
);
CREATE SEQUENCE IF NOT EXISTS tool_execution_SEQ START WITH 1 INCREMENT BY 50;
CREATE INDEX IF NOT EXISTS idx_tool_execution_trace ON tool_execution(trace_id);

-- Add trace_id column to existing tables for direct trace lookup
ALTER TABLE event ADD COLUMN IF NOT EXISTS trace_id UUID;
ALTER TABLE task ADD COLUMN IF NOT EXISTS trace_id UUID;
ALTER TABLE report ADD COLUMN IF NOT EXISTS trace_id UUID;
