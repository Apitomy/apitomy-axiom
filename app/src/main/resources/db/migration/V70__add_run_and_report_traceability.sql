-- Traceability for scheduled job runs (#424) and reports (#425).

-- Who triggered a run or report. Axiom has no authentication, so triggered_by is 'scheduler' or
-- 'manual'; triggered_by_trace_id holds the caller's trace when an agent triggered it manually.
ALTER TABLE scheduled_job_run ADD COLUMN triggered_by VARCHAR(255);
ALTER TABLE scheduled_job_run ADD COLUMN triggered_by_trace_id UUID;
UPDATE scheduled_job_run SET triggered_by = 'scheduler' WHERE run_trigger = 'scheduled';
UPDATE scheduled_job_run SET triggered_by = 'manual' WHERE run_trigger = 'manual';

-- Reports did not record their trigger before #425; existing rows keep NULL (unknown).
ALTER TABLE report ADD COLUMN report_trigger VARCHAR(32);
ALTER TABLE report ADD COLUMN triggered_by VARCHAR(255);
ALTER TABLE report ADD COLUMN triggered_by_trace_id UUID;

-- Correlation columns on AI usage and activity rows.
ALTER TABLE ai_usage ADD COLUMN scheduled_job_run_id BIGINT;
ALTER TABLE ai_usage ADD COLUMN report_id BIGINT;
ALTER TABLE activity_log ADD COLUMN scheduled_job_run_id BIGINT;
ALTER TABLE activity_log ADD COLUMN report_id BIGINT;
ALTER TABLE activity_log ADD COLUMN report_definition_id BIGINT;

CREATE INDEX idx_ai_usage_scheduled_job_run ON ai_usage(scheduled_job_run_id);
CREATE INDEX idx_ai_usage_report ON ai_usage(report_id);
CREATE INDEX idx_activity_log_scheduled_job_run ON activity_log(scheduled_job_run_id);
CREATE INDEX idx_activity_log_report ON activity_log(report_id);
