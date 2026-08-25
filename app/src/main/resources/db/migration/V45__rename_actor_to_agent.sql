-- Rename actor table to agent
ALTER TABLE actor RENAME TO agent;
ALTER TABLE agent RENAME COLUMN type TO agent_type;
ALTER TABLE agent DROP COLUMN permissions;
ALTER TABLE agent ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- Remove human actor records
DELETE FROM agent WHERE agent_type = 'human';

-- Update "ai-agent" type to actual provider type
UPDATE agent SET agent_type = 'claude-code' WHERE agent_type = 'ai-agent';

-- Rename task.assigned_actor to task.assigned_agent
ALTER TABLE task RENAME COLUMN assigned_actor TO assigned_agent;

-- Rename ai_usage.actor_id to ai_usage.agent_id
ALTER TABLE ai_usage RENAME COLUMN actor_id TO agent_id;

-- Rename executionMode "actor" to "agent" in action_type and scheduled_job tables
UPDATE action_type SET execution_mode = 'agent' WHERE execution_mode = 'actor';
UPDATE scheduled_job SET execution_mode = 'agent' WHERE execution_mode = 'actor';

-- Rename sequence
ALTER TABLE agent ALTER COLUMN id RESTART WITH (SELECT MAX(id) + 1 FROM agent);

-- Create agent_lease table for cross-workload busy tracking
CREATE TABLE agent_lease (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id    BIGINT NOT NULL,
    work_type   VARCHAR(32) NOT NULL,
    work_id     BIGINT NOT NULL,
    leased_at   TIMESTAMP NOT NULL,
    CONSTRAINT fk_agent_lease_agent FOREIGN KEY (agent_id) REFERENCES agent(id)
);
