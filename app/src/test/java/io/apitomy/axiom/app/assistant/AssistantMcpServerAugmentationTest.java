package io.apitomy.axiom.app.assistant;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssistantMcpServerAugmentationTest {

    @Test
    void axiomToolsDoNotReAddAxiomWhenAxiomAssistantIsPresent() {
        List<String> serverNames = new ArrayList<>(List.of("@axiom-assistant"));

        AssistantSessionManager.augmentServersFromAllowedTools(serverNames,
                List.of("mcp__axiom__axiom_list_tools", "mcp__axiom__axiom_get_tool"));

        assertEquals(List.of("@axiom-assistant"), serverNames);
    }

    @Test
    void axiomToolsImplyAxiomAssistantWhenNotConfigured() {
        List<String> serverNames = new ArrayList<>();

        AssistantSessionManager.augmentServersFromAllowedTools(serverNames,
                List.of("mcp__axiom__axiom_list_tools"));

        assertEquals(List.of("@axiom-assistant"), serverNames);
    }

    @Test
    void externalServersAreStillAutoIncluded() {
        List<String> serverNames = new ArrayList<>(List.of("@axiom-assistant"));

        AssistantSessionManager.augmentServersFromAllowedTools(serverNames,
                List.of("mcp__github__create_issue", "mcp__github__list_issues"));

        assertEquals(List.of("@axiom-assistant", "github"), serverNames);
    }
}
