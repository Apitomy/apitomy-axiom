package io.apitomy.axiom.agents.opencode;

import io.apitomy.axiom.agents.spi.AgentRequest;
import io.apitomy.axiom.agents.spi.AgentResult;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests model validation in {@link OpenCodeAgent}.
 */
class OpenCodeAgentModelValidationTest {

    @Test
    void modelWithoutProviderPrefixFailsClearly() throws Exception {
        OpenCodeAgent agent = new OpenCodeAgent();
        agent.defaultModel = Optional.empty();
        agent.defaultTimeoutSeconds = 5;
        AgentRequest request = AgentRequest.builder().prompt("hi").model("claude-sonnet-4").build();

        AgentResult result = agent.execute(request).get();

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("claude-sonnet-4"), result.errorMessage());
        assertTrue(result.errorMessage().contains("provider/model"), result.errorMessage());
        assertTrue(result.executionLog() != null && result.executionLog().contains("provider/model"));
    }
}
