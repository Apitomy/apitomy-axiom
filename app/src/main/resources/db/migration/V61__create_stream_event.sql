-- Normalized event stream: all events from all connections land here
-- with typed, schema-validated payloads.
CREATE TABLE stream_event (
    id UUID PRIMARY KEY,
    source_event_id VARCHAR(255) NOT NULL,
    source VARCHAR(32) NOT NULL,
    connection_id VARCHAR(63) NOT NULL REFERENCES event_source_connection(id),
    type VARCHAR(64) NOT NULL,
    ref VARCHAR(2048) NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    actor TEXT NOT NULL,
    payload TEXT NOT NULL,
    source_data TEXT,
    created_on TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX idx_stream_event_source_event_id ON stream_event(source_event_id);
CREATE INDEX idx_stream_event_type ON stream_event(type);
CREATE INDEX idx_stream_event_connection ON stream_event(connection_id);
CREATE INDEX idx_stream_event_ref ON stream_event(ref);
CREATE INDEX idx_stream_event_timestamp ON stream_event(timestamp);
