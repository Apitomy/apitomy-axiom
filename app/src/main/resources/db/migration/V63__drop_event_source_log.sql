-- Drop the event source polling log table (replaced by the new
-- event stream pipeline which uses stream_event for all events).
-- The old event_source, event, and event_queue tables are retained
-- because other subsystems still reference them.
DROP TABLE IF EXISTS event_source_log;
