package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.agents.spi.AgentRegistry;
import io.apitomy.axiom.core.entities.AgentCapabilityEntity;
import io.apitomy.axiom.core.entities.AgentEntity;
import io.apitomy.axiom.core.entities.AgentLeaseEntity;
import io.apitomy.axiom.core.util.GlobMatcher;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Concurrency governance service for the agent pool. Manages lease-based
 * assignment of configured agent slots to work items (tasks, reports,
 * scheduled jobs). Each agent slot can only execute one work item at a time.
 *
 * <p>Resolution order when selecting an agent for a work item:</p>
 * <ol>
 *   <li>Explicit assignment: if an agent entity ID is provided, use that agent</li>
 *   <li>Capability match: find an idle agent whose capabilities match the
 *       requested capability via glob patterns (e.g. "openapi:*")</li>
 * </ol>
 *
 * <p>No fallback tier — if no matching agent is available, the lease request fails.</p>
 */
@ApplicationScoped
public class AgentPool {

    private static final Logger LOG = Logger.getLogger(AgentPool.class);

    @Inject
    AgentRegistry agentRegistry;

    /**
     * Cleans up orphaned leases on startup (e.g. from a previous crash).
     */
    @Transactional
    void cleanup(@Observes StartupEvent event) {
        long orphaned = AgentLeaseEntity.deleteAll();
        if (orphaned > 0) {
            LOG.infof("Cleaned up %d orphaned agent lease(s) from previous run", orphaned);
        }
    }

    /**
     * Attempts to lease an agent for executing a work item.
     *
     * @param capability    the capability to match (e.g. action type name), or null
     * @param agentEntityId explicit agent entity ID assignment, or null for auto-resolve
     * @param workloadType  the type of workload (e.g. "task", "report")
     * @param workloadId    the ID of the workload being executed
     * @return an AgentLease if a slot is available, or empty if all agents are busy
     */
    @Transactional
    public Optional<AgentLease> tryLease(String capability, Long agentEntityId,
                                         String workloadType, Long workloadId) {
        // Tier 1: explicit agent assignment
        if (agentEntityId != null) {
            AgentEntity agent = AgentEntity.findById(agentEntityId);
            if (agent != null && agent.enabled && !isLeased(agent.id)) {
                return Optional.of(createLease(agent, workloadType, workloadId));
            }
            return Optional.empty();
        }

        // Tier 2: capability match via glob patterns
        List<AgentEntity> agents = AgentEntity.list("enabled = true ORDER BY id ASC");
        for (AgentEntity agent : agents) {
            if (!isLeased(agent.id)) {
                List<String> capabilities = AgentCapabilityEntity.findCapabilities(agent.id);
                if (capability == null || GlobMatcher.anyMatches(capabilities, capability)) {
                    return Optional.of(createLease(agent, workloadType, workloadId));
                }
            }
        }

        // No fallback — strict matching
        if (capability != null) {
            LOG.warnf("No agent with capability matching '%s' is available", capability);
        }
        return Optional.empty();
    }

    /**
     * Releases a previously acquired lease, making the agent slot available
     * for new work items.
     *
     * @param lease the lease to release
     */
    @Transactional
    public void release(AgentLease lease) {
        if (lease == null) {
            return;
        }
        long deleted = AgentLeaseEntity.delete("agentId", lease.agentEntityId());
        if (deleted > 0) {
            LOG.infof("Released agent '%s' (ID: %d)",
                    lease.agentEntityName(), lease.agentEntityId());
        }
    }

    /**
     * Checks whether an agent entity is currently leased (has an active lease).
     *
     * @param agentEntityId the agent entity ID to check
     * @return true if the agent has an active lease
     */
    public boolean isLeased(Long agentEntityId) {
        return AgentLeaseEntity.count("agentId", agentEntityId) > 0;
    }

    /**
     * Creates a lease for an agent to execute a workload.
     *
     * @param agentEntity  the agent entity to lease
     * @param workloadType the type of workload (e.g. "task", "report")
     * @param workloadId   the ID of the workload being executed
     * @return the created AgentLease
     */
    private AgentLease createLease(AgentEntity agentEntity, String workloadType, Long workloadId) {
        // Determine the agent type from the entity
        String resolvedType = agentEntity.agentType;
        Agent agent = agentRegistry.getAgent(resolvedType);

        // Create the lease row
        AgentLeaseEntity lease = new AgentLeaseEntity();
        lease.agentId = agentEntity.id;
        lease.workType = workloadType != null ? workloadType : "general";
        lease.workId = workloadId != null ? workloadId : 0L;
        lease.leasedAt = Instant.now();
        lease.persist();

        LOG.infof("Leased agent '%s' (ID: %d, type: %s) for workload '%s:%d'",
                agentEntity.name, agentEntity.id, resolvedType, workloadType, workloadId);
        return new AgentLease(agentEntity.id, agentEntity.name, agent);
    }

}
