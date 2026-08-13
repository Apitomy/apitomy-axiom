CREATE TABLE IF NOT EXISTS retention_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    closed_project_retention_days INT NOT NULL DEFAULT 90,
    trace_retention_days INT NOT NULL DEFAULT 30,
    event_retention_days INT NOT NULL DEFAULT 90,
    event_source_log_retention_days INT NOT NULL DEFAULT 7
);

CREATE SEQUENCE IF NOT EXISTS retention_config_SEQ START WITH 1 INCREMENT BY 50;
