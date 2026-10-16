package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.AgentCapabilityEntity;
import io.apitomy.axiom.core.entities.AgentEntity;
import io.apitomy.axiom.core.entities.AgentLeaseEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link AgentPool} honours the action type's engine when choosing an agent.
 */
@QuarkusTest
class AgentPoolTest {

    @Inject
    AgentPool agentPool;

    private final List<Long> disabled = new ArrayList<>();
    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    void isolateAgents() {
        QuarkusTransaction.requiringNew().run(() -> {
            AgentLeaseEntity.deleteAll();
            for (AgentEntity agent : AgentEntity.<AgentEntity>list("enabled = true")) {
                agent.enabled = false;
                disabled.add(agent.id);
            }
        });
    }

    @AfterEach
    void restoreAgents() {
        QuarkusTransaction.requiringNew().run(() -> {
            AgentLeaseEntity.deleteAll();
            for (Long id : created) {
                AgentCapabilityEntity.delete("agentId", id);
                AgentEntity.deleteById(id);
            }
            for (Long id : disabled) {
                AgentEntity agent = AgentEntity.findById(id);
                if (agent != null) {
                    agent.enabled = true;
                }
            }
        });
        created.clear();
        disabled.clear();
    }

    private long agent(String name, String type) {
        long id = QuarkusTransaction.requiringNew().call(() -> {
            AgentEntity agent = new AgentEntity();
            agent.name = name;
            agent.agentType = type;
            agent.enabled = true;
            agent.persist();
            AgentCapabilityEntity.setCapabilities(agent.id, List.of("*"));
            return agent.id;
        });
        created.add(id);
        return id;
    }

    private Optional<AgentLease> lease(String engine, Long assigned) {
        return QuarkusTransaction.requiringNew().call(() ->
                agentPool.tryLease("action:x", engine, assigned, "task", 1L));
    }

    @Test
    void engineSelectsAgentOfThatType() {
        agent("pool-claude", "claude-code");
        long opencode = agent("pool-opencode", "opencode");

        AgentLease lease = lease("opencode", null).orElseThrow();
        assertEquals(opencode, lease.agentEntityId());
        assertEquals("opencode", lease.agent().getType());
    }

    @Test
    void blankEngineAcceptsAnyType() {
        long first = agent("pool-any-1", "claude-code");
        agent("pool-any-2", "opencode");

        assertEquals(first, lease(null, null).orElseThrow().agentEntityId());
        assertTrue(lease("", null).isPresent());
    }

    @Test
    void busyAgentsOfOtherTypesAreNotUsed() {
        long opencode = agent("pool-busy-oc", "opencode");
        agent("pool-busy-cc", "claude-code");
        assertEquals(opencode, lease("opencode", null).orElseThrow().agentEntityId());

        assertFalse(lease("opencode", null).isPresent(), "the only opencode agent is leased");
    }

    @Test
    void explicitAssignmentOfAnotherTypeIsIgnored() {
        long claude = agent("pool-assigned-cc", "claude-code");
        long opencode = agent("pool-assigned-oc", "opencode");

        assertEquals(opencode, lease("opencode", claude).orElseThrow().agentEntityId());
    }

    @Test
    void hasEnabledAgentReportsWhetherTheEngineIsConfigured() {
        agent("pool-has-cc", "claude-code");

        assertTrue(QuarkusTransaction.requiringNew().call(() -> agentPool.hasEnabledAgent("claude-code")));
        assertFalse(QuarkusTransaction.requiringNew().call(() -> agentPool.hasEnabledAgent("opencode")));
        assertTrue(QuarkusTransaction.requiringNew().call(() -> agentPool.hasEnabledAgent(null)));
    }
}
