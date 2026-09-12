package io.apitomy.axiom.agents.opencode;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests model list resolution behavior in {@link OpenCodeAgent}.
 */
class OpenCodeAgentAvailableModelsTest {

    @Test
    void fallsBackToConfiguredModelsWhenDiscoveryIsDisabled() {
        OpenCodeAgent agent = new OpenCodeAgent();
        agent.executable = "opencode";
        agent.modelDiscoveryEnabled = false;
        agent.modelDiscoveryTimeoutSeconds = 8;
        agent.modelDiscoveryCacheSeconds = 86400;
        agent.availableModels = "github-copilot/claude-sonnet-5, github-copilot/kimi-k3";

        List<String> models = agent.getAvailableModels();

        assertEquals(List.of("github-copilot/claude-sonnet-5", "github-copilot/kimi-k3"), models);
        assertEquals("configured", agent.getModelSource());
    }

    @Test
    void usesDynamicallyDiscoveredModelsWhenAvailable() throws Exception {
        OpenCodeAgent agent = new OpenCodeAgent();
        agent.availableModels = "github-copilot/claude-sonnet-5";

        OpenCodeModelDiscovery discovery = new OpenCodeModelDiscovery(
                "opencode",
                true,
                8,
                86400,
                Clock.systemUTC(),
                (executable, timeout) -> new OpenCodeModelDiscovery.CommandResult(
                        0,
                        "openai/gpt-5.4\nopenai/gpt-5.4-mini\n")
        );
        setField(agent, "modelDiscovery", discovery);

        List<String> models = agent.getAvailableModels();

        assertEquals(List.of("openai/gpt-5.4", "openai/gpt-5.4-mini"), models);
        assertEquals("dynamic", agent.getModelSource());
    }

    @Test
    void refreshAvailableModelsForcesDiscoveryRefresh() throws Exception {
        OpenCodeAgent agent = new OpenCodeAgent();
        AtomicInteger executions = new AtomicInteger();

        OpenCodeModelDiscovery discovery = new OpenCodeModelDiscovery(
                "opencode",
                true,
                8,
                86400,
                Clock.systemUTC(),
                (executable, timeout) -> {
                    if (executions.getAndIncrement() == 0) {
                        return new OpenCodeModelDiscovery.CommandResult(0, "openai/gpt-5.4\n");
                    }
                    return new OpenCodeModelDiscovery.CommandResult(0, "openai/gpt-5.5\n");
                }
        );
        setField(agent, "modelDiscovery", discovery);

        List<String> initial = agent.getAvailableModels();
        agent.refreshAvailableModels();
        List<String> refreshed = agent.getAvailableModels();

        assertEquals(List.of("openai/gpt-5.4"), initial);
        assertEquals(List.of("openai/gpt-5.5"), refreshed);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
