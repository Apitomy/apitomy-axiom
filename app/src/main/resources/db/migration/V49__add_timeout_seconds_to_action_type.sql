-- =============================================================================
-- V49: Add timeoutSeconds column to action_type
-- Allows per-action-type overrides for the agent execution timeout.
-- When NULL, the global default (120s) applies.
-- =============================================================================

ALTER TABLE action_type ADD COLUMN IF NOT EXISTS timeout_seconds INTEGER;
