package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.agents.spi.AgentRegistry;
import io.quarkus.scheduler.Scheduled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link OpenCodeModelCacheRefresher}.
 */
class OpenCodeModelCacheRefresherTest {

    @Test
    void refreshesOpenCodeOnStartup() {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        Agent openCode = mock(Agent.class);
        when(openCode.getType()).thenReturn("opencode");
        when(agentRegistry.getAllAgents()).thenReturn(List.of(openCode));

        OpenCodeModelCacheRefresher refresher = new OpenCodeModelCacheRefresher(agentRegistry);

        refresher.refreshOnStartup(null);

        verify(openCode, times(1)).refreshAvailableModels();
    }

    @Test
    void scheduledRefreshSkipsNonOpenCodeAgents() {
        AgentRegistry agentRegistry = mock(AgentRegistry.class);
        Agent claude = mock(Agent.class);
        when(claude.getType()).thenReturn("claude-code");
        when(agentRegistry.getAllAgents()).thenReturn(List.of(claude));

        OpenCodeModelCacheRefresher refresher = new OpenCodeModelCacheRefresher(agentRegistry);

        refresher.refreshDaily();

        verify(claude, times(0)).refreshAvailableModels();
    }

    @Test
    void scheduledRefreshUsesConfigurableInterval() throws Exception {
        Method refreshDailyMethod = OpenCodeModelCacheRefresher.class
                .getDeclaredMethod("refreshDaily");
        Scheduled scheduled = refreshDailyMethod.getAnnotation(Scheduled.class);

        assertEquals("${axiom.agent.opencode.model-discovery.refresh-interval:24h}",
                scheduled.every());
    }
}
