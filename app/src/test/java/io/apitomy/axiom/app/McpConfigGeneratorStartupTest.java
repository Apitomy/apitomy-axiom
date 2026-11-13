package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.opencode.OpenCodeMcpManager;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the engine MCP managers get their delegates at startup, before any workflow task has used
 * {@link McpConfigGenerator}. Callers such as the run summary service rely on the OpenCode delegate to register
 * MCP servers. Runs in its own profile so the application starts fresh.
 */
@QuarkusTest
@TestProfile(McpConfigGeneratorStartupTest.FreshStart.class)
class McpConfigGeneratorStartupTest {

    /** A distinct profile, forcing a fresh application start. */
    public static class FreshStart implements QuarkusTestProfile {
    }

    @Inject
    OpenCodeMcpManager openCodeMcpManager;

    /**
     * The OpenCode MCP manager has a delegate as soon as the application has started.
     *
     * @throws Exception on reflection failure
     */
    @Test
    void openCodeDelegateIsRegisteredAtStartup() throws Exception {
        OpenCodeMcpManager manager = ClientProxy.unwrap(openCodeMcpManager);
        Field delegate = OpenCodeMcpManager.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        assertNotNull(delegate.get(manager), "McpConfigGenerator must register its delegate at startup");
    }
}
