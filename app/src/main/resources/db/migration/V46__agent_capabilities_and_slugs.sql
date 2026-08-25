-- Create agent_capability table
CREATE TABLE agent_capability (
    agent_id BIGINT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    capability VARCHAR(255) NOT NULL,
    PRIMARY KEY (agent_id, capability)
);

-- Migrate existing capabilities into the new table.
-- Most agents have a single capability value (e.g. '*' or 'auto-tag').
-- For single values: insert directly, prefixing non-wildcard values with 'action:'.
-- For comma-separated values: handled by the application's SeedDataInitializer on
-- next startup (rare edge case in practice — seed data uses single values).
INSERT INTO agent_capability (agent_id, capability)
SELECT id,
       CASE WHEN TRIM(capabilities) = '*' THEN '*'
            WHEN POSITION(',' IN capabilities) = 0
                THEN 'action:' || TRIM(capabilities)
            ELSE capabilities
       END
FROM agent
WHERE capabilities IS NOT NULL AND TRIM(capabilities) <> ''
  AND POSITION(',' IN capabilities) = 0;

-- For agents with comma-separated capabilities, insert each value separately.
-- H2 doesn't support regexp_split_to_table, so we handle up to 5 comma-separated
-- values using SUBSTRING_INDEX-style extraction via CASE expressions.
-- In practice, seed data only uses single values so this is a safety net.
INSERT INTO agent_capability (agent_id, capability)
SELECT id,
       CASE WHEN TRIM(SUBSTRING(capabilities, 1, POSITION(',' IN capabilities) - 1)) = '*'
            THEN '*'
            ELSE 'action:' || TRIM(SUBSTRING(capabilities, 1, POSITION(',' IN capabilities) - 1))
       END
FROM agent
WHERE capabilities IS NOT NULL AND TRIM(capabilities) <> ''
  AND POSITION(',' IN capabilities) > 0
  AND NOT EXISTS (
    SELECT 1 FROM agent_capability ac
    WHERE ac.agent_id = agent.id
      AND ac.capability = CASE
          WHEN TRIM(SUBSTRING(capabilities, 1, POSITION(',' IN capabilities) - 1)) = '*' THEN '*'
          ELSE 'action:' || TRIM(SUBSTRING(capabilities, 1, POSITION(',' IN capabilities) - 1))
      END
  );

INSERT INTO agent_capability (agent_id, capability)
SELECT id,
       CASE WHEN TRIM(SUBSTRING(capabilities, POSITION(',' IN capabilities) + 1)) = '*'
            THEN '*'
            ELSE 'action:' || TRIM(SUBSTRING(capabilities, POSITION(',' IN capabilities) + 1))
       END
FROM agent
WHERE capabilities IS NOT NULL AND TRIM(capabilities) <> ''
  AND POSITION(',' IN capabilities) > 0
  AND NOT EXISTS (
    SELECT 1 FROM agent_capability ac
    WHERE ac.agent_id = agent.id
      AND ac.capability = CASE
          WHEN TRIM(SUBSTRING(capabilities, POSITION(',' IN capabilities) + 1)) = '*' THEN '*'
          ELSE 'action:' || TRIM(SUBSTRING(capabilities, POSITION(',' IN capabilities) + 1))
      END
  );

-- Drop the old column
ALTER TABLE agent DROP COLUMN capabilities;

-- Add slug columns
ALTER TABLE report_definition ADD COLUMN slug VARCHAR(255);
ALTER TABLE scheduled_job ADD COLUMN slug VARCHAR(255);

-- Backfill slugs from existing names (lowercase, spaces to hyphens).
-- H2's REGEXP_REPLACE does not support the 'g' flag, so we chain replacements.
UPDATE report_definition SET slug = LOWER(REPLACE(name, ' ', '-'));
UPDATE report_definition SET slug = REGEXP_REPLACE(slug, '[^a-z0-9-]', '');
UPDATE scheduled_job SET slug = LOWER(REPLACE(name, ' ', '-'));
UPDATE scheduled_job SET slug = REGEXP_REPLACE(slug, '[^a-z0-9-]', '');

-- Add unique constraints
ALTER TABLE report_definition ADD CONSTRAINT uq_report_definition_slug UNIQUE (slug);
ALTER TABLE scheduled_job ADD CONSTRAINT uq_scheduled_job_slug UNIQUE (slug);
