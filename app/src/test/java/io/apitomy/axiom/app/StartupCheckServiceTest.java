package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.agents.spi.AgentCheckResult;
import io.apitomy.axiom.agents.spi.AgentRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the engine-level aggregation in {@link StartupCheckService}.
 */
class StartupCheckServiceTest {

    private static StartupCheckService serviceWith(Agent... agents) {
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.getAllAgents()).thenReturn(List.of(agents));
        StartupCheckService service = new StartupCheckService();
        service.agentRegistry = registry;
        service.checkAllEngines();
        return service;
    }

    private static Agent agent(String type, String status) {
        Agent agent = mock(Agent.class);
        when(agent.getType()).thenReturn(type);
        when(agent.healthCheck()).thenReturn(List.of(
                new AgentCheckResult(type + " check", status, type + " " + status)));
        return agent;
    }

    private static String systemStatus(StartupCheckService service, String type) {
        return service.getResults().stream()
                .filter(r -> r.name().equals(type + " check"))
                .findFirst().orElseThrow().status();
    }

    /**
     * An engine whose only problem is a warning counts as healthy, so the
     * system-level errors of the other engines are downgraded and the UI
     * opens, even though no engine was verified. Each engine's own results
     * keep the true status, which is what engines[].available reads.
     */
    @Test
    void warningOnlyEngineDowngradesOtherEnginesErrors() {
        StartupCheckService service = serviceWith(
                agent("claude-code", "warning"), agent("copilot", "error"));

        assertEquals("warning", systemStatus(service, "claude-code"));
        assertEquals("warning", systemStatus(service, "copilot"));
        assertFalse(service.hasErrors());
        assertEquals("error", service.getResultsForEngine("copilot").get(0).status());
    }

    /**
     * With no healthy engine every error stays an error, so the UI keeps
     * its configuration page.
     */
    @Test
    void errorsStayWhenNoEngineIsHealthy() {
        StartupCheckService service = serviceWith(
                agent("claude-code", "error"), agent("copilot", "error"));

        assertEquals("error", systemStatus(service, "claude-code"));
        assertEquals("error", systemStatus(service, "copilot"));
        assertTrue(service.hasErrors());
    }

    /**
     * A verified engine downgrades the other engines' errors.
     */
    @Test
    void okEngineDowngradesOtherEnginesErrors() {
        StartupCheckService service = serviceWith(
                agent("claude-code", "ok"), agent("copilot", "error"));

        assertEquals("warning", systemStatus(service, "copilot"));
        assertFalse(service.hasErrors());
    }
}
