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
