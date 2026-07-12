-- ============================================================
-- V25: Add model column to session template
-- ============================================================

ALTER TABLE session_template ADD COLUMN IF NOT EXISTS model VARCHAR(255);
