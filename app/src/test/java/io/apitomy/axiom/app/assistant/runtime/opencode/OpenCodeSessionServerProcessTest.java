package io.apitomy.axiom.app.assistant.runtime.opencode;

import io.apitomy.axiom.agents.opencode.OpenCodeServerManager;
import io.apitomy.axiom.app.assistant.AssistantContextBuilder.McpServerConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeSessionServerProcessTest {

    @Test
    void startsAndStopsSessionScopedOpenCodeServer() {
        Assumptions.assumeTrue(OpenCodeServerManager.isOpenCodeAvailable());
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess("opencode", "127.0.0.1", 0, 30);

        process.start();

        assertTrue(process.isAlive());
        assertTrue(process.baseUrl().startsWith("http://127.0.0.1:"));

        process.stop();

        assertFalse(process.isAlive());
    }

    @Test
    void processBuilderIncludesExtraEnvironmentAndServeArguments() {
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                "opencode", "127.0.0.1", 0, 30, Map.of("OPENCODE_CONFIG", "/tmp/s/opencode.json"));

        ProcessBuilder builder = process.createProcessBuilder(4321);

        assertEquals(List.of("opencode", "serve", "--hostname", "127.0.0.1", "--port", "4321"),
                builder.command());
        assertEquals("/tmp/s/opencode.json", builder.environment().get("OPENCODE_CONFIG"));
    }

    @Test
    void realOpenCodeLoadsMcpServersFromGeneratedConfig(@TempDir Path sessionDir) throws Exception {
        Assumptions.assumeTrue(OpenCodeServerManager.isOpenCodeAvailable());
        Path config = OpenCodeConfigWriter.writeConfig(sessionDir, Map.of(
                "axiom-it-bogus", McpServerConfig.stdio("/nonexistent/axiom-it-cmd", List.of(), Map.of())));
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                "opencode", "127.0.0.1", 0, 30, Map.of("OPENCODE_CONFIG", config.toString()));
        try {
            process.start();
            Map<String, OpenCodeAssistantClient.McpServerStatus> statuses =
                    new OpenCodeAssistantClient(process.baseUrl()).mcpStatus();

            assertTrue(statuses.containsKey("axiom-it-bogus"), "status map: " + statuses);
            assertEquals("failed", statuses.get("axiom-it-bogus").status());
        } finally {
            process.stop();
        }
    }
}
