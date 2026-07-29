CREATE TABLE IF NOT EXISTS event_source_label (
    event_source_id BIGINT NOT NULL,
    label VARCHAR(255) NOT NULL,
    UNIQUE (event_source_id, label),
    FOREIGN KEY (event_source_id) REFERENCES event_source(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_event_source_label ON event_source_label(label, event_source_id);
