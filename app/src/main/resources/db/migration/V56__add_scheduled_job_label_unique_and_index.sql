-- scheduled_job_label was created (V36) without the UNIQUE constraint and index
-- that every other *_label join table has. Bring it in line with the rest of the schema.

-- Remove any duplicate (scheduled_job_id, label) rows that may already exist so the
-- UNIQUE constraint below can be added safely. The table has no primary key, so use
-- H2's built-in _ROWID_ pseudo-column to keep the lowest-rowid row of each group and
-- delete only the extras. This touches no rows when there are no duplicates (the normal
-- case, and always the case on fresh installs), and never empties the table, so there is
-- no window in which the data could be lost if the migration is interrupted.
DELETE FROM scheduled_job_label
    WHERE _ROWID_ NOT IN (
        SELECT MIN(_ROWID_) FROM scheduled_job_label GROUP BY scheduled_job_id, label
    );

ALTER TABLE scheduled_job_label ADD CONSTRAINT IF NOT EXISTS uq_scheduled_job_label UNIQUE (scheduled_job_id, label);

CREATE INDEX IF NOT EXISTS idx_scheduled_job_label ON scheduled_job_label(label, scheduled_job_id);
