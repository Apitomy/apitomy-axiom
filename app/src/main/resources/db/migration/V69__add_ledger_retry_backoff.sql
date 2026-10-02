-- Limited retries with exponential backoff and attempt history (#422).
-- attempt_count counts routing attempts (first try included); next_attempt_at is set only
-- while an entry is 'failed' and waiting for a retry. 'exhausted' is the new terminal status
-- of an entry whose last allowed attempt failed.
ALTER TABLE event_processing_ledger ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE event_processing_ledger ADD COLUMN last_attempt_at TIMESTAMP;
ALTER TABLE event_processing_ledger ADD COLUMN next_attempt_at TIMESTAMP;

ALTER TABLE routing_outcome ADD COLUMN attempt_number INT;

-- Before #422 every failed attempt recorded exactly one failed outcome and attempts stopped at
-- the first failure, so an outcome belongs to attempt 1 + (failed outcomes recorded before it).
UPDATE routing_outcome SET attempt_number = 1 + (
    SELECT COUNT(*) FROM routing_outcome f
    WHERE f.ledger_id = routing_outcome.ledger_id AND f.status = 'failed'
      AND f.id < routing_outcome.id);

-- Attempts made so far: one per failed outcome, plus the successful one of a completed entry.
UPDATE event_processing_ledger SET attempt_count = (
    SELECT COUNT(*) FROM routing_outcome o
    WHERE o.ledger_id = event_processing_ledger.id AND o.status = 'failed')
    + CASE WHEN status = 'completed' THEN 1 ELSE 0 END;

UPDATE event_processing_ledger SET last_attempt_at = processed_on WHERE attempt_count > 0;

-- Entries at the default cap of 3 attempts are exhausted (a migration cannot read the configured
-- axiom.stream-pipeline.max-attempts; such entries can be retried manually). Other failed
-- entries are due immediately; one at or over a lower configured cap gets one more attempt.
UPDATE event_processing_ledger SET status = 'exhausted'
    WHERE status = 'failed' AND attempt_count >= 3;
UPDATE event_processing_ledger SET next_attempt_at = CURRENT_TIMESTAMP WHERE status = 'failed';

CREATE INDEX idx_epl_status_next ON event_processing_ledger(status, next_attempt_at);
