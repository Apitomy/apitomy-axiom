package io.apitomy.axiom.app.assistant.runtime;

import io.apitomy.axiom.app.assistant.AssistantEventParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                "Session"
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
                "Session"
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
                "Session"
        ));

        Object process = extractProcess(driver);
        assertEquals(0, getField(process, "configuredPort"));
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
