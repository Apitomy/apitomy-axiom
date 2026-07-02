-- Add structured human task fields for the Inbox feature
ALTER TABLE task ADD COLUMN IF NOT EXISTS human_context TEXT;
ALTER TABLE task ADD COLUMN IF NOT EXISTS output_schema TEXT;
