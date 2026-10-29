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
     * Engine errors are never downgraded: the flat list, the per-engine results
     * and the log all carry the true status, even when another engine works.
     */
    @Test
    void engineErrorsKeepTheirTrueStatusInEveryList() {
        StartupCheckService service = serviceWith(
                agent("claude-code", "ok"), agent("copilot", "error"));

        assertEquals("ok", systemStatus(service, "claude-code"));
        assertEquals("error", systemStatus(service, "copilot"));
        assertEquals("error", service.getResultsForEngine("copilot").get(0).status());
        assertTrue(service.anyEngineAvailable());
    }

    /**
     * A warning-only engine is available (it has no errors), matching
     * engines[].available; the other engine's error is still reported as an error.
     */
    @Test
    void warningOnlyEngineIsAvailableAndOtherErrorsStay() {
        StartupCheckService service = serviceWith(
                agent("claude-code", "warning"), agent("copilot", "error"));

        assertEquals("warning", systemStatus(service, "claude-code"));
        assertEquals("error", systemStatus(service, "copilot"));
        assertTrue(service.anyEngineAvailable());
    }

    /**
     * With every engine failing, no engine is available.
     */
    @Test
    void noEngineAvailableWhenAllFail() {
        StartupCheckService service = serviceWith(
                agent("claude-code", "error"), agent("copilot", "error"));

        assertFalse(service.anyEngineAvailable());
    }
}
