-- =============================================================================
-- V65: Migrate legacy BIGINT event_id columns to stream event UUIDs (#414)
-- =============================================================================
--
-- V64 dropped the legacy `event` table. Events now live in `stream_event`,
-- keyed by UUID. The `event_id` columns below were BIGINT references into the
-- dropped table, so any existing values are dead references that cannot be
-- mapped to a stream event. They are intentionally discarded: each column is
-- dropped and re-added as a nullable UUID.
--
-- No foreign key to stream_event(id) is added. Stream events are purged by
-- StreamEventCleanup according to the event retention policy, while traces,
-- activity, tasks and AI usage records are retained independently. These
-- columns are therefore soft correlation references: a dangling UUID simply
-- means the originating event has aged out (readers already handle a missing
-- event gracefully), and keeping the value preserves the correlation between
-- records that came from the same event.
-- =============================================================================

-- trace.event_id
DROP INDEX IF EXISTS idx_trace_event;
ALTER TABLE trace DROP COLUMN event_id;
ALTER TABLE trace ADD COLUMN event_id UUID;
CREATE INDEX idx_trace_event ON trace(event_id);

-- activity_log.event_id
DROP INDEX IF EXISTS idx_activity_log_event;
ALTER TABLE activity_log DROP COLUMN event_id;
ALTER TABLE activity_log ADD COLUMN event_id UUID;
CREATE INDEX idx_activity_log_event ON activity_log(event_id);

-- task.event_id
ALTER TABLE task DROP COLUMN event_id;
ALTER TABLE task ADD COLUMN event_id UUID;
CREATE INDEX idx_task_event ON task(event_id);

-- ai_usage.event_id
ALTER TABLE ai_usage DROP COLUMN event_id;
ALTER TABLE ai_usage ADD COLUMN event_id UUID;
CREATE INDEX idx_ai_usage_event ON ai_usage(event_id);

-- trace_node.entity_id: widen to VARCHAR so a node can reference either a
-- numeric entity (task, activity-log, ...) or a UUID entity (stream event).
-- Existing numeric values are preserved as their decimal string form.
DROP INDEX IF EXISTS idx_trace_node_entity;
ALTER TABLE trace_node ALTER COLUMN entity_id VARCHAR(64);
CREATE INDEX idx_trace_node_entity ON trace_node(entity_type, entity_id);
