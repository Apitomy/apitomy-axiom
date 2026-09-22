-- EventSourceConnection: an authenticated link to an external system
-- (GitHub, Jira) that produces a normalized event stream.
-- The id column is a user-provided slug (lowercase letters, numbers, dashes).
CREATE TABLE event_source_connection (
    id VARCHAR(63) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    source_type VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    base_url VARCHAR(1024) NOT NULL,
    secret_name VARCHAR(255),
    poll_interval INTEGER,
    configuration TEXT NOT NULL,
    last_polled_at TIMESTAMP,
    created_on TIMESTAMP NOT NULL,
    modified_on TIMESTAMP NOT NULL
);

-- Poll log: audits each poll cycle per connection
CREATE TABLE connection_poll_log (
    id BIGINT PRIMARY KEY,
    connection_id VARCHAR(63) NOT NULL REFERENCES event_source_connection(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL,
    message TEXT NOT NULL,
    detail TEXT,
    events_ingested INTEGER,
    duration_ms BIGINT,
    created_on TIMESTAMP NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS connection_poll_log_SEQ START WITH 1 INCREMENT BY 50;

CREATE INDEX idx_cpl_connection ON connection_poll_log(connection_id);
CREATE INDEX idx_cpl_created ON connection_poll_log(created_on);
