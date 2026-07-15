-- ============================================================
-- V26: Add init script fields to session template
-- ============================================================

ALTER TABLE session_template ADD COLUMN IF NOT EXISTS init_script TEXT;
ALTER TABLE session_template ADD COLUMN IF NOT EXISTS init_script_type VARCHAR(10);
