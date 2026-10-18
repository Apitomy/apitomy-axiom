-- Axiom 3.x incremental migration 2.
--
-- AI usage records gain the workflow run and workflow definition that produced them, so usage can be
-- summarized per workflow.
ALTER TABLE ai_usage ADD COLUMN workflow_run_id BIGINT NULL;
ALTER TABLE ai_usage ADD COLUMN workflow_definition_id BIGINT NULL;
CREATE INDEX idx_ai_usage_workflow_def_created ON ai_usage (workflow_definition_id, created_on);

-- Additional dashboard indexes (not related to AI usage): event source health looks up the latest poll per
-- source and status, and event volume/health counts recent events per source.
CREATE INDEX idx_espl_source_status_created ON event_source_poll_log (event_source_id, status, created_on);
CREATE INDEX idx_stream_event_source_created ON stream_event (event_source_id, created_on);
