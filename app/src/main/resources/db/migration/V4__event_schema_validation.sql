-- Axiom 3.x incremental migration 4.
--
-- Event schema validation: store each event's validation errors, and make source event IDs unique per
-- event source instead of globally (two sources watching the same project each keep their own events).
ALTER TABLE stream_event ADD COLUMN validation_errors TEXT;

DROP INDEX idx_stream_event_source_event_id;
CREATE UNIQUE INDEX idx_stream_event_source_event ON stream_event (event_source_id, source_event_id);
