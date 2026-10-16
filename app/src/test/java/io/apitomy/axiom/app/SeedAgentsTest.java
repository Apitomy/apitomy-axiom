package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.AgentCapabilityEntity;
import io.apitomy.axiom.core.entities.AgentEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the agents seeded on first startup and the message used when no agent of an engine exists.
 */
@QuarkusTest
class SeedAgentsTest {

    @Test
    void seedsTwoClaudeCodeAndTwoOpenCodeAgents() {
        Map<String, String> expected = Map.of(
                "Blinky", "claude-code", "Clyde", "claude-code",
                "Pinky", "opencode", "Inky", "opencode");
        QuarkusTransaction.requiringNew().run(() -> expected.forEach((name, type) -> {
            AgentEntity agent = AgentEntity.find("name", name).firstResult();
            assertEquals(type, agent != null ? agent.agentType : null, name);
            assertTrue(agent.enabled, name);
            assertEquals(List.of("*"), AgentCapabilityEntity.findCapabilities(agent.id), name);
        }));
    }

    @Test
    void noAgentMessageNamesTheMissingEngine() {
        assertEquals("No enabled agent of type 'opencode' is configured for action type 'triage'. "
                + "Add one under Components → Agents.", TaskExecutionService.noAgentMessage("opencode", "triage"));
        assertEquals("No agent available for task type: triage", TaskExecutionService.noAgentMessage(null, "triage"));
    }
}
