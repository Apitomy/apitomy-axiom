-- Drop the old event system tables, fully replaced by the new event
-- stream pipeline (stream_event, event_source_connection, event_subscription).
-- Drop order respects FK constraints.

DROP TABLE IF EXISTS event_source_label;
DROP TABLE IF EXISTS event_queue;
DROP TABLE IF EXISTS event;
DROP TABLE IF EXISTS event_source;

DROP SEQUENCE IF EXISTS event_SEQ;
DROP SEQUENCE IF EXISTS event_queue_SEQ;
DROP SEQUENCE IF EXISTS event_source_SEQ;
