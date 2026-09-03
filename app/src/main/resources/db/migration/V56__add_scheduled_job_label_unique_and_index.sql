-- scheduled_job_label was created (V36) without the UNIQUE constraint and index
-- that every other *_label join table has. Bring it in line with the rest of the schema.

-- Remove any duplicate (scheduled_job_id, label) rows that may already exist so the
-- UNIQUE constraint below can be added safely. The table has no primary key, so
-- rebuild its contents from a distinct copy held in a session-scoped temporary table.
-- H2 auto-commits DDL, so a temp table guarantees a repaired re-run starts clean.
CREATE LOCAL TEMPORARY TABLE scheduled_job_label_dedup AS
    SELECT DISTINCT scheduled_job_id, label FROM scheduled_job_label;

DELETE FROM scheduled_job_label;

INSERT INTO scheduled_job_label (scheduled_job_id, label)
    SELECT scheduled_job_id, label FROM scheduled_job_label_dedup;

DROP TABLE scheduled_job_label_dedup;

ALTER TABLE scheduled_job_label ADD CONSTRAINT IF NOT EXISTS uq_scheduled_job_label UNIQUE (scheduled_job_id, label);

CREATE INDEX IF NOT EXISTS idx_scheduled_job_label ON scheduled_job_label(label, scheduled_job_id);
