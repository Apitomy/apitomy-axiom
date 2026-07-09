-- ============================================================
-- V24: Create session template tables for AI Assistant
-- ============================================================

CREATE TABLE IF NOT EXISTS session_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    system_prompt TEXT NOT NULL,
    welcome_message TEXT,
    working_directory VARCHAR(1024)
);

CREATE SEQUENCE IF NOT EXISTS session_template_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS session_template_mcp_server (
    session_template_id BIGINT NOT NULL,
    mcp_server_name VARCHAR(255) NOT NULL,
    UNIQUE (session_template_id, mcp_server_name),
    FOREIGN KEY (session_template_id) REFERENCES session_template(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS session_template_allowed_tool (
    session_template_id BIGINT NOT NULL,
    tool_pattern VARCHAR(255) NOT NULL,
    UNIQUE (session_template_id, tool_pattern),
    FOREIGN KEY (session_template_id) REFERENCES session_template(id) ON DELETE CASCADE
);
