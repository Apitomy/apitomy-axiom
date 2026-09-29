package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.agents.opencode.OpenCodeServerManager;
import io.apitomy.axiom.app.assistant.AssistantContextBuilder.McpServerConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void processBuilderUsesWorkingDirectoryWhenProvided(@TempDir Path workDir) {
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                "opencode", "127.0.0.1", 0, 30, Map.of(), workDir);

        ProcessBuilder builder = process.createProcessBuilder(4321);

        assertEquals(workDir.toFile(), builder.directory());
    }

    @Test
    void processBuilderInheritsDirectoryWhenWorkingDirectoryIsNull() {
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                "opencode", "127.0.0.1", 0, 30, Map.of(), null);

        ProcessBuilder builder = process.createProcessBuilder(4321);

        assertNull(builder.directory());
    }

    @Test
    void realOpenCodeUsesProcessWorkingDirectory(@TempDir Path workDir) throws Exception {
        Assumptions.assumeTrue(OpenCodeServerManager.isOpenCodeAvailable());
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                "opencode", "127.0.0.1", 0, 30, Map.of(), workDir);
        try {
            process.start();
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(process.baseUrl() + "/path")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(), response.body());

            String directory = new ObjectMapper().readTree(response.body()).path("directory").asText();
            assertEquals(workDir.toRealPath().toString(), Path.of(directory).toRealPath().toString());
        } finally {
            process.stop();
        }
    }
}
