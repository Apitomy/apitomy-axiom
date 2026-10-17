package io.apitomy.axiom.app.assistant;

import io.apitomy.axiom.core.entities.ToolsetEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

/**
 * Verifies that session template allowed tools are resolved through the shared
 * {@code ToolsetResolver} (nested expansion, cycle detection, de-duplication).
 */
@QuarkusTest
class AssistantToolsetResolutionTest {

    @Inject
    AssistantSessionManager sessionManager;

    private static String unique(String base) {
        return base + "-" + UUID.randomUUID();
    }

    private static void createToolset(String name, String tools) {
        QuarkusTransaction.requiringNew().run(() -> {
            ToolsetEntity toolset = new ToolsetEntity();
            toolset.name = name;
            toolset.tools = tools;
            toolset.persist();
        });
    }

    private static SessionTemplateService.SessionTemplate template(List<String> allowedTools) {
        return new SessionTemplateService.SessionTemplate("t", "T", null, null, null, null,
                null, null, null, null, null, null, List.of(), allowedTools, false, Set.of());
    }

    @Test
    void nestedToolsetsExpandFully() {
        String base = unique("Base");
        String dev = unique("Dev");
        createToolset(base, "Read, Grep");
        createToolset(dev, "@" + base + ", Edit");

        List<String> resolved = QuarkusTransaction.requiringNew().call(() ->
                sessionManager.resolveAllowedTools(template(List.of("@" + dev, "Bash"))));

        assertEquals(List.of("Read", "Grep", "Edit", "Bash"), resolved);
    }

    @Test
    void cyclicToolsetsDoNotLoop() {
        String a = unique("A");
        String b = unique("B");
        createToolset(a, "Read, @" + b);
        createToolset(b, "Write, @" + a);

        List<String> resolved = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                QuarkusTransaction.requiringNew().call(() ->
                        sessionManager.resolveAllowedTools(template(List.of("@" + a)))));

        assertEquals(List.of("Read", "Write"), resolved);
    }

    @Test
    void duplicatesAreRemoved() {
        String one = unique("One");
        String two = unique("Two");
        createToolset(one, "Read, Grep");
        createToolset(two, "Grep, Read, Edit");

        List<String> resolved = QuarkusTransaction.requiringNew().call(() ->
                sessionManager.resolveAllowedTools(
                        template(List.of("Read", "@" + one, "@" + two, "Edit"))));

        assertEquals(List.of("Read", "Grep", "Edit"), resolved);
    }

    @Test
    void unknownToolsetsAreSkipped() {
        List<String> resolved = QuarkusTransaction.requiringNew().call(() ->
                sessionManager.resolveAllowedTools(
                        template(List.of("@" + unique("Missing"), "Bash"))));

        assertEquals(List.of("Bash"), resolved);
    }
}
