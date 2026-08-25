-- Create agent_capability table
CREATE TABLE agent_capability (
    agent_id BIGINT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    capability VARCHAR(255) NOT NULL,
    PRIMARY KEY (agent_id, capability)
);

-- Migrate existing comma-separated capabilities into the new table.
-- Prefix non-wildcard values with 'action:'.
INSERT INTO agent_capability (agent_id, capability)
SELECT a.id, CASE WHEN TRIM(c.cap) = '*' THEN '*' ELSE 'action:' || TRIM(c.cap) END
FROM agent a,
     LATERAL regexp_split_to_table(a.capabilities, ',') AS c(cap)
WHERE a.capabilities IS NOT NULL AND a.capabilities <> '';

-- Drop the old column
ALTER TABLE agent DROP COLUMN capabilities;

-- Add slug columns
ALTER TABLE report_definition ADD COLUMN slug VARCHAR(255);
ALTER TABLE scheduled_job ADD COLUMN slug VARCHAR(255);

-- Backfill slugs from existing names (lowercase, spaces to hyphens, strip non-alphanumeric)
UPDATE report_definition SET slug = LOWER(REGEXP_REPLACE(REPLACE(name, ' ', '-'), '[^a-z0-9-]', '', 'g'));
UPDATE scheduled_job SET slug = LOWER(REGEXP_REPLACE(REPLACE(name, ' ', '-'), '[^a-z0-9-]', '', 'g'));

-- Add unique constraints
ALTER TABLE report_definition ADD CONSTRAINT uq_report_definition_slug UNIQUE (slug);
ALTER TABLE scheduled_job ADD CONSTRAINT uq_scheduled_job_slug UNIQUE (slug);
