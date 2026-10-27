-- Axiom 3.x incremental migration 5.
--
-- Record the AI model an agent engine reported it actually ran for each task (#524).
ALTER TABLE task ADD COLUMN actual_model VARCHAR(255);
