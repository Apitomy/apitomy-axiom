package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the composition item helpers.
 */
class CompositionItemsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void supportedTypesAreWorkflowActionTypeAndTool() {
        assertEquals(ConfigItemType.WORKFLOW, CompositionItems.supportedType("workflow"));
        assertEquals(ConfigItemType.ACTION_TYPE, CompositionItems.supportedType("action-type"));
        assertEquals(ConfigItemType.TOOL, CompositionItems.supportedType("tool"));
        assertThrows(IllegalArgumentException.class, () -> CompositionItems.supportedType("toolset"));
        assertThrows(IllegalArgumentException.class, () -> CompositionItems.supportedType("nope"));
    }

    @Test
    void blankWorkflowHasStartAndEnd() {
        ObjectNode workflow = CompositionItems.blank(ConfigItemType.WORKFLOW, "wf", MAPPER);
        assertEquals("wf", workflow.path("name").asText());
        assertEquals(2, workflow.path("nodes").size());
        assertEquals("start", workflow.path("nodes").get(0).path("type").asText());
        assertTrue(CompositionItems.revisionOf(workflow).startsWith("sha256:"));
    }

    @Test
    void blankActionTypeAndToolCarryTheirName() {
        assertEquals("at", CompositionItems.nameOf(CompositionItems.blank(ConfigItemType.ACTION_TYPE, "at", MAPPER)));
        assertEquals("node", CompositionItems.blank(ConfigItemType.ACTION_TYPE, "at", MAPPER)
                .path("executionMode").asText());
        assertEquals("t", CompositionItems.nameOf(CompositionItems.blank(ConfigItemType.TOOL, "t", MAPPER)));
    }

    @Test
    void blankActionTypeUsesTheRequestedExecutionMode() {
        ObjectNode agent = CompositionItems.blank(ConfigItemType.ACTION_TYPE, "a", "agent", MAPPER);
        assertEquals("agent", agent.path("executionMode").asText());
        assertTrue(agent.has("promptTemplate"));
        ObjectNode script = CompositionItems.blank(ConfigItemType.ACTION_TYPE, "s", "script", MAPPER);
        assertEquals("script", script.path("executionMode").asText());
        assertTrue(script.path("scriptTemplate").asText().startsWith("#!/usr/bin/env bash"));
        ObjectNode node = CompositionItems.blank(ConfigItemType.ACTION_TYPE, "n", "node", MAPPER);
        assertTrue(node.has("codeTemplate"));
    }

    @Test
    void nameOfIgnoresBlank() throws Exception {
        assertNull(CompositionItems.nameOf(MAPPER.readTree("{\"name\":\"  \"}")));
        assertNull(CompositionItems.nameOf(MAPPER.readTree("{}")));
        assertEquals("x", CompositionItems.nameOf(MAPPER.readTree("{\"name\":\" x \"}")));
    }

    @Test
    void workflowPackItemWrapsTheDocument() throws Exception {
        JsonNode doc = MAPPER.readTree("{\"id\":\"w\",\"name\":\"Doc name\",\"description\":\"d\",\"nodes\":[],\"edges\":[]}");
        ObjectNode item = CompositionItems.toPackItem(ConfigItemType.WORKFLOW, "wf-def", doc, MAPPER);
        assertEquals("wf-def", item.path("name").asText());
        assertEquals("d", item.path("description").asText());
        assertEquals(doc, item.path("content"));
        assertEquals("workflowDefinitions", CompositionItems.packArray(ConfigItemType.WORKFLOW));
    }

    @Test
    void actionTypePackItemIsTheContent() throws Exception {
        JsonNode content = MAPPER.readTree("{\"name\":\"at\",\"executionMode\":\"node\"}");
        assertEquals(content, CompositionItems.toPackItem(ConfigItemType.ACTION_TYPE, "at", content, MAPPER));
        assertEquals("actionTypes", CompositionItems.packArray(ConfigItemType.ACTION_TYPE));
        assertEquals("tools", CompositionItems.packArray(ConfigItemType.TOOL));
    }

    @Test
    void findsReferencedActionTypesExcludingSdk() throws Exception {
        JsonNode workflow = MAPPER.readTree("""
                {"nodes":[{"id":"a","type":"action","config":{"actionType":"triage"}},
                          {"id":"b","type":"action","config":{"actionType":"sdk:add-label"}},
                          {"id":"c","type":"action","config":{"actionType":"triage"}},
                          {"id":"s","type":"start","config":{}}]}
                """);
        assertEquals(Set.of("triage"), CompositionItems.referencedActionTypes(workflow));
    }

    @Test
    void findsReferencedToolsInStringOrArrayAllowedTools() throws Exception {
        JsonNode asString = MAPPER.readTree(
                "{\"allowedTools\":\"Read, mcp__axiom-tools__gh-labels mcp__axiom-tools__gh-comment\"}");
        assertEquals(Set.of("gh-labels", "gh-comment"), CompositionItems.referencedTools(asString));
        JsonNode asArray = MAPPER.readTree("{\"allowedTools\":[\"mcp__axiom-tools__x\",\"Bash\"]}");
        assertEquals(Set.of("x"), CompositionItems.referencedTools(asArray));
        assertFalse(CompositionItems.referencedTools(MAPPER.readTree("{}")).iterator().hasNext());
    }

    @Test
    void subscriptionIsSupportedWithABlankItemAndPackArray() {
        assertEquals(ConfigItemType.SUBSCRIPTION, CompositionItems.supportedType("subscription"));
        assertEquals("subscriptions", CompositionItems.packArray(ConfigItemType.SUBSCRIPTION));
        ObjectNode blank = CompositionItems.blank(ConfigItemType.SUBSCRIPTION, "on-pr", new ObjectMapper());
        assertEquals("on-pr", blank.path("name").asText());
        assertEquals("", blank.path("description").asText());
        assertEquals("", blank.path("filterExpression").asText());
        assertTrue(blank.path("routing").isArray());
        assertEquals(0, blank.path("routing").size());
        assertFalse(blank.has("enabled"));
        assertFalse(blank.has("scriptTemplate"));
    }

    @Test
    void referencedWorkflowsAreTheCreateWorkflowNames() throws Exception {
        JsonNode sub = new ObjectMapper().readTree("""
            {"name": "s", "routing": [
              {"type": "create-workflow", "workflowDefinitionName": "wf-a"},
              {"type": "workflow-dispatch"},
              {"type": "create-workflow", "workflowDefinitionId": 7}]}
            """);
        assertEquals(Set.of("wf-a"), CompositionItems.referencedWorkflows(sub));
    }
}
