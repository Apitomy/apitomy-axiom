-- Add missing AI configuration fields to report_definition
ALTER TABLE report_definition ADD COLUMN engine VARCHAR(255);
ALTER TABLE report_definition ADD COLUMN model VARCHAR(255);
ALTER TABLE report_definition ADD COLUMN max_steps INTEGER;
ALTER TABLE report_definition ADD COLUMN max_budget_usd DOUBLE PRECISION;

-- Add missing timeout_seconds to scheduled_job
ALTER TABLE scheduled_job ADD COLUMN timeout_seconds INTEGER;
