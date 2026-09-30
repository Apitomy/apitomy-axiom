package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                    new OpenCodeAssistantClient(process.baseUrl(), process.password()).mcpStatus();

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
    void realOpenCodeReportsProviderCatalog(@TempDir Path workDir) throws Exception {
        Assumptions.assumeTrue(OpenCodeServerManager.isOpenCodeAvailable());
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                "opencode", "127.0.0.1", 0, 30, Map.of(), workDir);
        try {
            process.start();
            OpenCodeAssistantClient.ProviderCatalog catalog =
                    new OpenCodeAssistantClient(process.baseUrl(), process.password()).providerCatalog();

            assertFalse(catalog.models().isEmpty());
            catalog.models().forEach((provider, models) ->
                    assertFalse(models.isEmpty(), "provider has no models: " + provider));
        } finally {
            process.stop();
        }
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
                    authorized(process, "/path"),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(), response.body());

            String directory = new ObjectMapper().readTree(response.body()).path("directory").asText();
            assertEquals(workDir.toRealPath().toString(), Path.of(directory).toRealPath().toString());
        } finally {
            process.stop();
        }
    }

    @Test
    void realOpenCodeLoadsSessionPermissionsFromGeneratedConfig(@TempDir Path sessionDir) throws Exception {
        Assumptions.assumeTrue(OpenCodeServerManager.isOpenCodeAvailable());
        Path config = OpenCodeConfigWriter.writeConfig(sessionDir, Map.of(),
                OpenCodeSessionPermissions.fromAllowedTools(List.of("Read(*)", "Bash(ls *)")));
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                "opencode", "127.0.0.1", 0, 30, Map.of("OPENCODE_CONFIG", config.toString()), sessionDir);
        try {
            process.start();
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            HttpResponse<String> response = client.send(
                    authorized(process, "/config"),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            JsonNode permission = new ObjectMapper().readTree(response.body()).path("permission");
            assertEquals("ask", permission.path("*").asText(), response.body());
            assertEquals("allow", permission.path("read").asText(), response.body());
            assertEquals("allow", permission.path("bash").path("ls *").asText(), response.body());
        } finally {
            process.stop();
        }
    }

    @Test
    void processBuilderSetsServerPasswordOverridingExtraEnvironment() {
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                "opencode", "127.0.0.1", 0, 30, Map.of("OPENCODE_SERVER_PASSWORD", "template-value"));

        ProcessBuilder builder = process.createProcessBuilder(4321);

        assertNotNull(process.password());
        assertEquals(process.password(), builder.environment().get("OPENCODE_SERVER_PASSWORD"));
        assertNotEquals("template-value", process.password());
    }

    @Test
    void generatesLongUniquePasswordPerInstance() {
        OpenCodeSessionServerProcess first = new OpenCodeSessionServerProcess("opencode", "127.0.0.1", 0, 30);
        OpenCodeSessionServerProcess second = new OpenCodeSessionServerProcess("opencode", "127.0.0.1", 0, 30);

        assertTrue(first.password().length() >= 32, "password too short");
        assertNotEquals(first.password(), second.password());
    }

    @Test
    void realOpenCodeRequiresPassword(@TempDir Path workDir) throws Exception {
        Assumptions.assumeTrue(OpenCodeServerManager.isOpenCodeAvailable());
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                "opencode", "127.0.0.1", 0, 30, Map.of(), workDir);
        try {
            process.start();
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            HttpResponse<String> unauthenticated = client.send(
                    HttpRequest.newBuilder(URI.create(process.baseUrl() + "/global/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(401, unauthenticated.statusCode());
            assertTrue(new OpenCodeAssistantClient(process.baseUrl(), process.password()).health().healthy());
        } finally {
            process.stop();
        }
    }

    @Test
    void retriesStartWhenProcessExitsDuringStartup(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(OpenCodeServerManager.isOpenCodeAvailable());
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path marker = tempDir.resolve("runs");
        Path script = tempDir.resolve("flaky-opencode.sh");
        Files.writeString(script, "#!/bin/sh\n"
                + "echo run >> '" + marker + "'\n"
                + "if [ \"$(wc -l < '" + marker + "')\" -le 1 ]; then exit 1; fi\n"
                + "exec opencode \"$@\"\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                script.toString(), "127.0.0.1", 0, 30, Map.of(), tempDir);
        try {
            process.start();

            assertTrue(process.isAlive());
            assertEquals(2, Files.readAllLines(marker).size());
        } finally {
            process.stop();
        }
    }

    @Test
    void doesNotRetryWhenPortIsConfigured(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path marker = tempDir.resolve("runs");
        Path script = tempDir.resolve("failing-opencode.sh");
        Files.writeString(script, "#!/bin/sh\necho run >> '" + marker + "'\nexit 1\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                script.toString(), "127.0.0.1", 45999, 10, Map.of(), tempDir);

        IllegalStateException error = assertThrows(IllegalStateException.class, process::start);

        assertTrue(error.getMessage().contains("exited during startup"), error.getMessage());
        assertFalse(error.getMessage().contains(process.password()));
        assertEquals(1, Files.readAllLines(marker).size());
    }

    @Test
    void givesUpAfterThreeStartAttempts(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path marker = tempDir.resolve("runs");
        Path script = tempDir.resolve("failing-opencode.sh");
        Files.writeString(script, "#!/bin/sh\necho run >> '" + marker + "'\nexit 1\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                script.toString(), "127.0.0.1", 0, 10, Map.of(), tempDir);

        assertThrows(IllegalStateException.class, process::start);

        assertEquals(3, Files.readAllLines(marker).size());
    }

    private static HttpRequest authorized(OpenCodeSessionServerProcess process, String path) {
        String credentials = Base64.getEncoder().encodeToString(
                ("opencode:" + process.password()).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(process.baseUrl() + path))
                .header("Authorization", "Basic " + credentials)
                .GET()
                .build();
    }
}
