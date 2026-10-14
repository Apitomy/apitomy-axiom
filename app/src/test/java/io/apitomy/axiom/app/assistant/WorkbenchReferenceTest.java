package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.WorkflowTemplates;
import io.apitomy.flow.changeset.ChangeSetResult;
import io.apitomy.flow.changeset.ChangeSets;
import io.apitomy.flow.changeset.ContentRevision;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    void templateAllowsSuggestingButNotRunningTests() throws Exception {
        String tools = template().path("allowedTools").toString();
        assertTrue(tools.contains("mcp__workbench__suggest_test"));
        assertFalse(tools.contains("test_action_type"));
        assertFalse(tools.contains("test_tool"));
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
                "Never describe the output format in an agent prompt",
                "applied by the user's editor when your turn ends",
                "Do not set or adjust node positions; the user's editor lays out the workflow when your changes are applied.",
                "Do not mark an edge as the default unless its node also has conditional edges",
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
}
