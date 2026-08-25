package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.Agent;

/**
 * Value record representing an active lease of an agent from the pool.
 * The lease ties a configured agent entity (a pool slot) to its runtime
 * Agent implementation for the duration of a single work item execution.
 *
 * @param agentEntityId   the database ID of the AgentEntity slot
 * @param agentEntityName the human-readable name of the agent slot
 * @param agent           the resolved Agent implementation
 */
public record AgentLease(Long agentEntityId, String agentEntityName, Agent agent) {
}
