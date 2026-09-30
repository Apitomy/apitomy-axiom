package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenCodeSessionPermissionsTest {

    @Test
    void emptyOrNullAllowedToolsProduceNoPermissionBlock() {
        assertNull(OpenCodeSessionPermissions.fromAllowedTools(null));
        assertNull(OpenCodeSessionPermissions.fromAllowedTools(List.of()));
        assertNull(OpenCodeSessionPermissions.fromAllowedTools(List.of(" ")));
    }

    @Test
    void configAssistantToolsMapToAllowRulesWithAskDefault() {
        JsonNode permission = OpenCodeSessionPermissions.fromAllowedTools(List.of(
                "Read(*)", "Write(*)", "Edit(*)", "Bash(ls *)", "Bash(cat *)",
                "mcp__axiom__axiom_list_tools"));

        assertEquals("*", permission.fieldNames().next());
        assertEquals("ask", permission.path("*").asText());
        assertEquals("allow", permission.path("read").asText());
        assertEquals("allow", permission.path("edit").asText());
        assertEquals("ask", permission.path("bash").path("*").asText());
        assertEquals("allow", permission.path("bash").path("ls *").asText());
        assertEquals("allow", permission.path("bash").path("cat *").asText());
        assertEquals("allow", permission.path("axiom_axiom_list_tools").asText());
    }

    @Test
    void unrestrictedBashWinsOverPatterns() {
        JsonNode permission = OpenCodeSessionPermissions.fromAllowedTools(
                List.of("Bash(ls *)", "Bash(*)", "Bash(cat *)"));

        assertEquals("allow", permission.path("bash").asText());
    }

    @Test
    void bareNamesAndWebToolsMap() {
        JsonNode permission = OpenCodeSessionPermissions.fromAllowedTools(
                List.of("Read", "Glob", "Grep", "WebFetch(*)", "WebSearch(*)", "Task"));

        assertEquals("allow", permission.path("read").asText());
        assertEquals("allow", permission.path("glob").asText());
        assertEquals("allow", permission.path("grep").asText());
        assertEquals("allow", permission.path("webfetch").asText());
        assertEquals("allow", permission.path("websearch").asText());
        assertEquals("allow", permission.path("task").asText());
    }

    @Test
    void wholeMcpServerMapsToWildcardKey() {
        JsonNode permission = OpenCodeSessionPermissions.fromAllowedTools(
                List.of("mcp__github", "mcp__jira__*"));

        assertEquals("allow", permission.path("github_*").asText());
        assertEquals("allow", permission.path("jira_*").asText());
    }

    @Test
    void readWithPathPatternUsesObjectSyntax() {
        JsonNode permission = OpenCodeSessionPermissions.fromAllowedTools(List.of("Read(src/**)"));

        assertEquals("ask", permission.path("read").path("*").asText());
        assertEquals("allow", permission.path("read").path("src/**").asText());
    }

    @Test
    void unknownToolsAreSkipped() {
        JsonNode permission = OpenCodeSessionPermissions.fromAllowedTools(
                List.of("StructuredOutput", "Read(*)"));

        assertFalse(permission.has("structuredoutput"));
        assertFalse(permission.has("StructuredOutput"));
        assertEquals("allow", permission.path("read").asText());
    }
}
