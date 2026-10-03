-- Retention gaps and dangling references (#429).
--
-- New retention settings for history tables that were never cleaned up. 0 means "keep forever"
-- and is the default, so upgrading never deletes data the user did not ask to delete.
ALTER TABLE retention_config ADD COLUMN IF NOT EXISTS scheduled_job_run_retention_days INT NOT NULL DEFAULT 0;
ALTER TABLE retention_config ADD COLUMN IF NOT EXISTS report_retention_days INT NOT NULL DEFAULT 0;
ALTER TABLE retention_config ADD COLUMN IF NOT EXISTS workflow_run_retention_days INT NOT NULL DEFAULT 0;
ALTER TABLE retention_config ADD COLUMN IF NOT EXISTS activity_log_retention_days INT NOT NULL DEFAULT 0;
ALTER TABLE retention_config ADD COLUMN IF NOT EXISTS ai_usage_retention_days INT NOT NULL DEFAULT 0;

-- The event source log table was dropped long ago; its retention setting was never read.
ALTER TABLE retention_config DROP COLUMN IF EXISTS event_source_log_retention_days;

-- Indexes for the cleanup queries.
CREATE INDEX IF NOT EXISTS idx_sjr_created_on ON scheduled_job_run(created_on);
CREATE INDEX IF NOT EXISTS idx_report_created_on ON report(created_on);
CREATE INDEX IF NOT EXISTS idx_activity_log_created_on ON activity_log(created_on);
CREATE INDEX IF NOT EXISTS idx_ai_usage_created_on ON ai_usage(created_on);
CREATE INDEX IF NOT EXISTS idx_wf_run_trigger_event ON workflow_run(trigger_event_id);
CREATE INDEX IF NOT EXISTS idx_ro_trace ON routing_outcome(trace_id);
CREATE INDEX IF NOT EXISTS idx_roi_trace_node ON routing_outcome_item(trace_node_id);
CREATE INDEX IF NOT EXISTS idx_roi_workflow_run ON routing_outcome_item(workflow_run_id);
CREATE INDEX IF NOT EXISTS idx_wrr_trace_node ON workflow_run_resume(trace_node_id);
