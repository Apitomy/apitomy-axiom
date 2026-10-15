package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.WorkflowTemplates;
import io.apitomy.axiom.app.expressions.ExpressionService;
import io.apitomy.flow.changeset.ChangeSetResult;
import io.apitomy.flow.changeset.ChangeSets;
import io.apitomy.flow.changeset.ContentRevision;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workbench template carries its rules, and the change-set reference example is valid for Flow.
 */
class WorkbenchReferenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode template() throws Exception {
        try (InputStream is = WorkbenchReferenceTest.class.getResourceAsStream(
                "/templates/assistant-templates/axiom-workbench.json")) {
            return MAPPER.readTree(is);
        }
    }

    @Test
    void newWorkflowsDeclareSubjectAndEventBothRequired() {
        com.fasterxml.jackson.databind.node.ObjectNode wf =
                io.apitomy.axiom.app.WorkflowTemplates.emptyWorkflow("X", new com.fasterxml.jackson.databind.ObjectMapper());
        com.fasterxml.jackson.databind.JsonNode inputs = wf.path("nodes").get(0).path("config").path("inputs");
        assertEquals(2, inputs.size());
        assertEquals("subject", inputs.get(0).path("name").asText());
        assertEquals("object", inputs.get(0).path("type").asText());
        assertTrue(inputs.get(0).path("required").asBoolean());
        assertEquals("event", inputs.get(1).path("name").asText());
        assertTrue(inputs.get(1).path("required").asBoolean());
    }

    @Test
    void templateAllowsSuggestingButNotRunningTests() throws Exception {
        String tools = template().path("allowedTools").toString();
        assertTrue(tools.contains("mcp__workbench__suggest_test"));
        assertFalse(tools.contains("test_action_type"));
        assertFalse(tools.contains("test_tool"));
    }

    @Test
    void templateAllowsReadingRuns() throws Exception {
        String tools = template().path("allowedTools").toString();
        for (String tool : List.of("mcp__workbench__list_workflow_runs", "mcp__workbench__get_workflow_run",
                "mcp__workbench__get_trace")) {
            assertTrue(tools.contains("\"" + tool + "\""), tool);
        }
    }

    @Test
    void promptCarriesEveryRule() throws Exception {
        String prompt = template().path("systemPrompt").asText();
        for (String rule : List.of(
                "Edit workflows only with propose_workflow_changes",
                "as baseRevision",
                "inspect a real event of that type with get_stream_event and check the expression with evaluate_expression",
                "If no events of that type exist, say so and use the documented event schema",
                "Prefer node action types for non-AI steps",
                "Declare an input for everything an action uses",
                "- You cannot run action types or tools. After creating or changing a node or script action type or a tool, "
                        + "call suggest_test with realistic sample inputs so the user can run it. Never claim it works "
                        + "until the user reports a passing run.",
                "For each node or script action type and each tool you create or change, call suggest_test with "
                        + "realistic sample inputs.",
                "Receive-event nodes must correlate",
                "- Declare only the standard Start inputs (subject, event); require event only when the workflow "
                        + "must be started from an event.",
                "Never describe the output format in an agent prompt",
                "applied by the user's editor when your turn ends",
                "Do not set or adjust node positions; the user's editor lays out the workflow when your changes are applied.",
                "Do not mark an edge as the default unless its node also has conditional edges",
                "- When asked about a workflow run, read it with get_workflow_run (and get_trace when the cause is "
                        + "unclear) before changing anything; fix the cause in the workflow or its components, then "
                        + "summarise what failed and what you changed.",
                "{{reference}}",
                "{{changesetReference}}")) {
            assertTrue(prompt.contains(rule), "prompt must contain: " + rule);
        }
    }

    @Test
    void templateAllowsOnlyWorkbenchToolsAndJq() throws Exception {
        for (JsonNode tool : template().path("allowedTools")) {
            String name = tool.asText();
            assertTrue(name.startsWith("mcp__workbench__") || name.equals("Bash(jq *)"), name);
        }
        assertFalse(template().path("allowedTools").toString().contains("Write"));
    }

    /** The workbench reference lists every evaluate_expression scope with where it is used. */
    @Test
    void referenceListsEveryExpressionScope() {
        String reference = new AssistantReferenceGenerator().generateChangeSetReference();
        assertTrue(reference.contains("# Checking expressions"), reference);
        for (ExpressionService.Scope scope : ExpressionService.Scope.values()) {
            assertTrue(reference.contains("| `" + scope.jsonName() + "` | " + scope.description() + " |"),
                    scope.jsonName());
        }
        assertTrue(reference.contains("`context.subject` and `context.event`"), reference);
    }

    /** The evaluate_expression tool's scope parameter names every valid scope. */
    @Test
    void evaluateExpressionToolNamesEveryScope() throws Exception {
        String server;
        try (InputStream is = WorkbenchReferenceTest.class.getResourceAsStream(
                "/templates/axiom-workbench-mcp/server.js")) {
            server = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher scopeParam = Pattern.compile("\\{ name: \"scope\", type: \"string\", description: \"([^\"]*)\"")
                .matcher(server);
        assertTrue(scopeParam.find(), "evaluate_expression must declare a scope parameter");
        for (ExpressionService.Scope scope : ExpressionService.Scope.values()) {
            assertTrue(scopeParam.group(1).contains(scope.jsonName()), scope.jsonName());
        }
    }

    @Test
    void changeSetExampleAppliesToTheBlankWorkflow() throws Exception {
        String reference = new AssistantReferenceGenerator().generateChangeSetReference();
        Matcher json = Pattern.compile("```json\\n(\\{.*?\\})\\n```", Pattern.DOTALL).matcher(reference);
        assertTrue(json.find(), "reference must contain a json example");
        JsonNode workflow = WorkflowTemplates.emptyWorkflow("wf", MAPPER);
        com.fasterxml.jackson.databind.node.ObjectNode changeSet = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(json.group(1));
        changeSet.put("baseRevision", ContentRevision.of(workflow));
        assertInstanceOf(ChangeSetResult.Applied.class, ChangeSets.applyChecked(workflow, changeSet));
        for (String code : List.of("target-missing", "duplicate-id", "edge-endpoint-missing", "malformed", "stale")) {
            assertTrue(reference.contains(code), code);
        }
    }

    @Test
    void blankWorkflowEdgeIsNotADefaultEdge() {
        JsonNode edge = WorkflowTemplates.emptyWorkflow("wf", MAPPER).path("edges").get(0);
        assertFalse(edge.path("isDefault").asBoolean(true), "a single outgoing edge must not be marked default");
    }
}
