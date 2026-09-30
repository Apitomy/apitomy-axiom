package io.apitomy.axiom.app.assistant.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.assistant.AssistantContextBuilder;
import io.apitomy.axiom.app.assistant.AssistantEventParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveSessionDriverFactoryTest {

    @Test
    void createDriverPrefersAssistantOpenCodeKeysWhenPresent() throws Exception {
        InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory factory =
                new InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory();

        setField(factory, "assistantOpenCodeExecutable", "assistant-opencode");
        setField(factory, "openCodeExecutable", "legacy-opencode");
        setField(factory, "assistantOpenCodeStartupTimeoutSeconds", 17);
        setField(factory, "legacyAssistantOpenCodeServerStartupTimeoutSeconds", 41);
        setField(factory, "openCodeServerStartupTimeoutSeconds", 41);
        setField(factory, "openCodeServerHostname", "127.0.0.1");
        setField(factory, "openCodeServerPort", 0);

        InteractiveSessionDriver driver = factory.createDriver(new InteractiveSessionDriverFactory.DriverRequest(
                "opencode",
                "general-assistant",
                Path.of("/tmp/session"),
                Path.of("/tmp/work"),
                List.of(),
                Map.of(),
                null,
                null,
                event -> {
                },
                event -> {
                },
                "github-copilot/claude-sonnet-5",
                null,
                "Session",
                Map.of(),
                null,
                List.of()
        ));

        Object process = extractProcess(driver);
        assertEquals("assistant-opencode", getField(process, "executable"));
        assertEquals(17, getField(process, "startupTimeoutSeconds"));
    }

    @Test
    void createDriverFallsBackToLegacyOpenCodeKeys() throws Exception {
        InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory factory =
                new InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory();

        setField(factory, "assistantOpenCodeExecutable", Optional.empty());
        setField(factory, "openCodeExecutable", "legacy-opencode");
        setField(factory, "assistantOpenCodeStartupTimeoutSeconds", Optional.empty());
        setField(factory, "legacyAssistantOpenCodeServerStartupTimeoutSeconds", 29);
        setField(factory, "openCodeServerStartupTimeoutSeconds", 13);
        setField(factory, "openCodeServerHostname", "127.0.0.1");
        setField(factory, "openCodeServerPort", 0);

        InteractiveSessionDriver driver = factory.createDriver(new InteractiveSessionDriverFactory.DriverRequest(
                "opencode",
                "general-assistant",
                Path.of("/tmp/session"),
                Path.of("/tmp/work"),
                List.of(),
                Map.of(),
                null,
                null,
                event -> {
                },
                event -> {
                },
                "github-copilot/claude-sonnet-5",
                null,
                "Session",
                Map.of(),
                null,
                List.of()
        ));

        Object process = extractProcess(driver);
        assertEquals("legacy-opencode", getField(process, "executable"));
        assertEquals(29, getField(process, "startupTimeoutSeconds"));
    }

    @Test
    void createDriverUsesEphemeralPortWhenAssistantPortOverrideIsNotSet() throws Exception {
        InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory factory =
                new InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory();

        setField(factory, "assistantOpenCodeExecutable", Optional.empty());
        setField(factory, "openCodeExecutable", "legacy-opencode");
        setField(factory, "assistantOpenCodeStartupTimeoutSeconds", Optional.empty());
        setField(factory, "legacyAssistantOpenCodeServerStartupTimeoutSeconds", Optional.empty());
        setField(factory, "openCodeServerStartupTimeoutSeconds", 13);
        setField(factory, "assistantOpenCodeServerPort", Optional.empty());
        setField(factory, "openCodeServerHostname", "127.0.0.1");
        setField(factory, "openCodeServerPort", 4096);

        InteractiveSessionDriver driver = factory.createDriver(new InteractiveSessionDriverFactory.DriverRequest(
                "opencode",
                "general-assistant",
                Path.of("/tmp/session"),
                Path.of("/tmp/work"),
                List.of(),
                Map.of(),
                null,
                null,
                event -> {
                },
                event -> {
                },
                "github-copilot/claude-sonnet-5",
                null,
                "Session",
                Map.of(),
                null,
                List.of()
        ));

        Object process = extractProcess(driver);
        assertEquals(0, getField(process, "configuredPort"));
    }

    @Test
    void createDriverWritesOpenCodeConfigAndPointsServerAtIt(@TempDir Path sessionDir) throws Exception {
        InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory factory = defaultFactory();

        InteractiveSessionDriver driver = factory.createDriver(new InteractiveSessionDriverFactory.DriverRequest(
                "opencode", "axiom-config-assistant", sessionDir, sessionDir.resolve("work"),
                List.of(), Map.of(), null, null, event -> {
                }, event -> {
                }, "github-copilot/claude-sonnet-5", null, "Session",
                Map.of("axiom", AssistantContextBuilder.McpServerConfig.stdio("node", List.of("server.js"), Map.of())),
                null, List.of()));

        Path configFile = sessionDir.resolve("opencode.json");
        assertTrue(Files.exists(configFile));
        assertTrue(Files.readString(configFile).contains("\"axiom\""));

        Object process = extractProcess(driver);
        @SuppressWarnings("unchecked")
        Map<String, String> environment = (Map<String, String>) getField(process, "environment");
        assertEquals(configFile.toString(), environment.get("OPENCODE_CONFIG"));
        assertEquals(sessionDir.resolve("work"), getField(process, "workingDirectory"));

        @SuppressWarnings("unchecked")
        java.util.Set<String> expected = (java.util.Set<String>) getField(driver, "expectedMcpServers");
        assertEquals(java.util.Set.of("axiom"), expected);
    }

    @Test
    void createDriverSkipsOpenCodeConfigWhenNoMcpServers(@TempDir Path sessionDir) throws Exception {
        InteractiveSessionDriver driver = defaultFactory().createDriver(
                new InteractiveSessionDriverFactory.DriverRequest(
                        "opencode", "general-assistant", sessionDir, sessionDir, List.of(), Map.of(),
                        null, null, event -> {
                        }, event -> {
                        }, "github-copilot/claude-sonnet-5", null, "Session", Map.of(), null, List.of()));

        assertFalse(Files.exists(sessionDir.resolve("opencode.json")));
        Object process = extractProcess(driver);
        @SuppressWarnings("unchecked")
        Map<String, String> environment = (Map<String, String>) getField(process, "environment");
        assertFalse(environment.containsKey("OPENCODE_CONFIG"));
    }

    @Test
    void createDriverRunsOpenCodeInWorkingDirectoryWithSessionEnvironment(@TempDir Path sessionDir)
            throws Exception {
        Path workDir = Files.createDirectories(sessionDir.resolve("workDir"));

        InteractiveSessionDriver driver = defaultFactory().createDriver(
                new InteractiveSessionDriverFactory.DriverRequest(
                        "opencode", "project-assistant", sessionDir, workDir, List.of(),
                        Map.of("AXIOM_PROJECT_ID", "42", "TEMPLATE_SECRET", "s3cr3t"),
                        42L, "demo", event -> {
                        }, event -> {
                        }, "github-copilot/claude-sonnet-5", null, "Session", Map.of(), null, List.of()));

        Object process = extractProcess(driver);
        assertEquals(workDir, getField(process, "workingDirectory"));
        @SuppressWarnings("unchecked")
        Map<String, String> environment = (Map<String, String>) getField(process, "environment");
        assertEquals("42", environment.get("AXIOM_PROJECT_ID"));
        assertEquals("s3cr3t", environment.get("TEMPLATE_SECRET"));
        assertFalse(environment.containsKey("OPENCODE_CONFIG"));
    }

    @Test
    void createDriverPassesSystemPromptToOpenCodeDriver(@TempDir Path sessionDir) throws Exception {
        InteractiveSessionDriver driver = defaultFactory().createDriver(
                new InteractiveSessionDriverFactory.DriverRequest(
                        "opencode", "axiom-config-assistant", sessionDir, sessionDir, List.of(), Map.of(),
                        null, null, event -> {
                        }, event -> {
                        }, "github-copilot/claude-sonnet-5", null, "Session", Map.of(),
                        "You are the Axiom Configuration Assistant.", List.of()));

        assertEquals("You are the Axiom Configuration Assistant.", getField(driver, "systemPrompt"));
    }

    @Test
    void createDriverWritesPermissionsAndStopsSendingPromptTools(@TempDir Path sessionDir) throws Exception {
        InteractiveSessionDriver driver = defaultFactory().createDriver(
                new InteractiveSessionDriverFactory.DriverRequest(
                        "opencode", "axiom-config-assistant", sessionDir, sessionDir, List.of(), Map.of(),
                        null, null, event -> {
                        }, event -> {
                        }, "github-copilot/claude-sonnet-5", null, "Session", Map.of(), null,
                        List.of("Read(*)", "mcp__axiom__axiom_list_tools")));

        JsonNode config = new ObjectMapper().readTree(Files.readString(sessionDir.resolve("opencode.json")));
        assertEquals("allow", config.path("permission").path("axiom_axiom_list_tools").asText());
        assertNull(getField(driver, "tools"));
    }

    @Test
    void axiomManagedOpenCodeConfigOverridesTemplateEnvironment() {
        Map<String, String> result =
                InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory.buildOpenCodeEnvironment(
                        Map.of("OPENCODE_CONFIG", "/template/value.json", "A", "1"),
                        Path.of("/session/opencode.json"));

        assertEquals("/session/opencode.json", result.get("OPENCODE_CONFIG"));
        assertEquals("1", result.get("A"));
    }

    @Test
    void templateOpenCodeConfigIsKeptWhenAxiomWroteNoConfig() {
        Map<String, String> result =
                InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory.buildOpenCodeEnvironment(
                        Map.of("OPENCODE_CONFIG", "/template/value.json"), null);

        assertEquals("/template/value.json", result.get("OPENCODE_CONFIG"));
    }

    @Test
    void nullEnvironmentEntriesAreSkipped() {
        Map<String, String> requestEnvironment = new HashMap<>();
        requestEnvironment.put("KEEP", "yes");
        requestEnvironment.put("NULL_VALUE", null);
        requestEnvironment.put(null, "null-key");

        Map<String, String> result =
                InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory.buildOpenCodeEnvironment(
                        requestEnvironment, null);

        assertEquals(Map.of("KEEP", "yes"), result);
    }

    private static InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory defaultFactory()
            throws Exception {
        InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory factory =
                new InteractiveSessionDriverFactory.DefaultInteractiveSessionDriverFactory();
        setField(factory, "assistantOpenCodeExecutable", Optional.empty());
        setField(factory, "openCodeExecutable", "opencode");
        setField(factory, "assistantOpenCodeStartupTimeoutSeconds", Optional.empty());
        setField(factory, "legacyAssistantOpenCodeServerStartupTimeoutSeconds", Optional.empty());
        setField(factory, "openCodeServerStartupTimeoutSeconds", 30);
        setField(factory, "assistantOpenCodeServerPort", Optional.empty());
        setField(factory, "openCodeServerHostname", "127.0.0.1");
        setField(factory, "openCodeServerPort", 0);
        return factory;
    }

    private static Object extractProcess(InteractiveSessionDriver driver) throws Exception {
        Field serverProcessField = driver.getClass().getDeclaredField("serverProcess");
        serverProcessField.setAccessible(true);
        Object serverProcessHandle = serverProcessField.get(driver);

        Field delegateField = serverProcessHandle.getClass().getDeclaredField("delegate");
        delegateField.setAccessible(true);
        return delegateField.get(serverProcessHandle);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        if (field.getType().equals(Optional.class)) {
            if (value instanceof Optional<?>) {
                field.set(target, value);
            } else {
                field.set(target, Optional.of(value));
            }
            return;
        }
        field.set(target, value);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
