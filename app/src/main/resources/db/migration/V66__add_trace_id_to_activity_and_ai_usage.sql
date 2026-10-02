-- Correlation: link activity and AI usage rows to the trace (unit of work) that produced them.
-- No foreign key: TraceCleanup clears these references explicitly when traces age out.
ALTER TABLE activity_log ADD COLUMN trace_id UUID;
CREATE INDEX IF NOT EXISTS idx_activity_log_trace ON activity_log(trace_id);
ALTER TABLE ai_usage ADD COLUMN trace_id UUID;
CREATE INDEX IF NOT EXISTS idx_ai_usage_trace ON ai_usage(trace_id);
