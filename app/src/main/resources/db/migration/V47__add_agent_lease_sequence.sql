-- Add sequence for agent_lease ID generation.
-- PanacheEntity uses SEQUENCE strategy by default, but V45 created the table
-- with AUTO_INCREMENT. This sequence aligns with Hibernate's expectation.
-- Start from a value beyond any existing IDs to avoid conflicts.
CREATE SEQUENCE IF NOT EXISTS agent_lease_SEQ START WITH 100 INCREMENT BY 50;
