-- =============================================================================
-- V31: Add maxSteps and maxBudgetUsd columns to action_type
-- Allows per-action-type overrides for the global max-steps and max-budget-usd
-- defaults. When NULL, the global defaults apply.
-- =============================================================================

ALTER TABLE action_type ADD COLUMN IF NOT EXISTS max_steps INTEGER;
ALTER TABLE action_type ADD COLUMN IF NOT EXISTS max_budget_usd DOUBLE PRECISION;
