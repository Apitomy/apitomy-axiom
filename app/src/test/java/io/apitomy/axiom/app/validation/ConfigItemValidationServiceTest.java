package io.apitomy.axiom.app.validation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.filters.SubscriptionFilterEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests for {@link ConfigItemValidationService} (no Quarkus, no database).
 */
class ConfigItemValidationServiceTest {

    /** Matches the Quarkus ObjectMapper, which ignores unknown properties. */
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private ConfigItemValidationService service;

    @BeforeEach
    void setUp() {
        service = new ConfigItemValidationService();
        service.objectMapper = mapper;
        service.filterEvaluator = new SubscriptionFilterEvaluator();
    }

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    private static ValidationContext names(Set<String> tools, Set<String> toolsets) {
        return new ValidationContext(tools, toolsets, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), null);
    }

    @Test
    void githubEventSourceRequiresOwnerRepoEntries() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.EVENT_SOURCE, json("""
            {"id": "gh", "name": "GH", "sourceType": "github", "baseUrl": "https://api.github.com",
             "configuration": {"repositories": ["not-a-repo"]}}
            """), ValidationContext.unchecked());
        assertTrue(problems.stream().anyMatch(p -> p.isError()
                && p.path().equals("configuration.repositories[0]")));
    }

    @Test
    void validJiraEventSourcePasses() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.EVENT_SOURCE, json("""
            {"id": "jira-prod", "name": "Jira", "sourceType": "jira", "baseUrl": "https://x.atlassian.net",
             "configuration": {"projects": ["PROJ"]}}
            """), ValidationContext.unchecked());
        assertFalse(ConfigItemValidationService.hasErrors(problems), problems.toString());
    }

    @Test
    void actionTypeMayReferenceToolsetFromSameSession() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.ACTION_TYPE, json("""
            {"name": "triage", "executionMode": "agent", "promptTemplate": "Triage the issue",
             "allowedTools": ["@triage-tools"]}
            """), names(Set.of(), Set.of("triage-tools")));
        assertFalse(ConfigItemValidationService.hasErrors(problems), problems.toString());
    }

    @Test
    void actionTypeRejectsUnknownPromptPlaceholder() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.ACTION_TYPE, json("""
            {"name": "bad", "executionMode": "agent", "promptTemplate": "Subject {{subjectId}}"}
            """), ValidationContext.unchecked());
        assertTrue(ConfigItemValidationService.hasErrors(problems), problems.toString());
    }

    @Test
    void toolsetRejectsUnknownTool() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.TOOLSET, json("""
            {"name": "ts", "tools": ["mcp__axiom-tools__missing"]}
            """), names(Set.of("present"), Set.of()));
        assertTrue(problems.stream().anyMatch(p -> p.code().equals("UNKNOWN_TOOL") && p.path().equals("tools[0]")));
    }

    private static final String VALID_WORKFLOW = """
        {"name": "triage", "content": {
          "id": "triage", "name": "triage",
          "nodes": [
            {"id": "s", "type": "start", "name": "Start", "config": {}, "position": {"x": 0, "y": 0}},
            {"id": "a", "type": "action", "name": "Do", "config": {"actionType": "do-it"},
             "position": {"x": 200, "y": 0}},
            {"id": "e", "type": "end", "name": "End", "config": {}, "position": {"x": 400, "y": 0}}
          ],
          "edges": [
            {"id": "e1", "source": "s", "target": "a", "priority": 0, "isDefault": true},
            {"id": "e2", "source": "a", "target": "e", "priority": 0, "isDefault": true}
          ]}}
        """;

    private static ValidationContext actionTypes(String... names) {
        return new ValidationContext(Set.of(), Set.of(), Set.of(names), Set.of(), Set.of(), Set.of(), Set.of(), null);
    }

    @Test
    void validWorkflowPasses() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW, json(VALID_WORKFLOW),
                actionTypes("do-it"));
        assertFalse(ConfigItemValidationService.hasErrors(problems), problems.toString());
    }

    @Test
    void uppercaseNodeTypeIsRejectedWithHint() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW,
                json(VALID_WORKFLOW.replace("\"type\": \"start\"", "\"type\": \"START\"")), actionTypes("do-it"));
        assertTrue(problems.stream().anyMatch(p -> p.code().equals("WORKFLOW_PARSE_ERROR")
                && p.message().contains("lowercase")), problems.toString());
    }

    @Test
    void sourceNodeIdEdgesAreRejected() throws Exception {
        String bad = VALID_WORKFLOW.replace("\"source\": \"s\", \"target\": \"a\"",
                "\"sourceNodeId\": \"s\", \"targetNodeId\": \"a\"");
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW, json(bad), actionTypes("do-it"));
        assertTrue(problems.stream().anyMatch(p -> p.isError() && p.code().equals("MISSING_EDGE_SOURCE")
                && p.path().equals("content.edges[0]")), problems.toString());
    }

    @Test
    void missingContentIdIsRejected() throws Exception {
        String bad = VALID_WORKFLOW.replace("\"id\": \"triage\", ", "");
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW, json(bad), actionTypes("do-it"));
        assertTrue(ConfigItemValidationService.hasErrors(problems), problems.toString());
    }

    @Test
    void unknownActionTypeIsRejectedAtNodePath() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW, json(VALID_WORKFLOW),
                actionTypes("other"));
        assertTrue(problems.stream().anyMatch(p -> p.code().equals("UNKNOWN_ACTION_TYPE")
                && p.path().startsWith("content.nodes[1]")), problems.toString());
    }

    @Test
    void unknownSdkFunctionIsRejected() throws Exception {
        String bad = VALID_WORKFLOW.replace("\"actionType\": \"do-it\"", "\"actionType\": \"sdk:nope\"");
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW, json(bad), actionTypes("do-it"));
        assertTrue(problems.stream().anyMatch(p -> p.isError() && p.code().equals("UNKNOWN_SDK_FUNCTION")
                && p.path().equals("content.nodes[1].config.actionType")), problems.toString());
    }

    @Test
    void missingActionTypeIsRejected() throws Exception {
        String bad = VALID_WORKFLOW.replace("{\"actionType\": \"do-it\"}", "{}");
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW, json(bad), actionTypes("do-it"));
        assertTrue(problems.stream().anyMatch(p -> p.isError() && p.code().equals("MISSING_ACTION_TYPE")
                && p.path().equals("content.nodes[1].config.actionType")), problems.toString());
    }

    @Test
    void requiredBuiltInStartInputIsRejected() throws Exception {
        String bad = VALID_WORKFLOW.replace("\"name\": \"Start\", \"config\": {}",
                "\"name\": \"Start\", \"config\": {\"inputs\": [{\"name\": \"event\", \"required\": true}]}");
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW, json(bad), actionTypes("do-it"));
        assertTrue(problems.stream().anyMatch(p -> p.isError() && p.code().equals("INVALID_START_INPUT")
                && p.path().equals("content.nodes[0].config.inputs")), problems.toString());
    }

    @Test
    void unknownReceiveEventTypeIsWarning() throws Exception {
        String bad = VALID_WORKFLOW.replace(
                "\"type\": \"action\", \"name\": \"Do\", \"config\": {\"actionType\": \"do-it\"}",
                "\"type\": \"receive-event\", \"name\": \"Wait\", \"config\": {\"eventType\": \"issues\"}");
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW, json(bad), actionTypes("do-it"));
        assertTrue(problems.stream().anyMatch(p -> !p.isError() && p.code().equals("UNKNOWN_EVENT_TYPE")
                && p.path().equals("content.nodes[1].config.eventType")
                && p.message().contains("issue.created")), problems.toString());
    }

    private static ValidationContext withInputs() {
        return new ValidationContext(Set.of(), Set.of(), Set.of("do-it"), Set.of(), Set.of(), Set.of(), Set.of(),
                Map.of("do-it", Map.of("ref", true, "note", false)));
    }

    private static String withNodeInputs(String inputs) {
        return VALID_WORKFLOW.replace("{\"actionType\": \"do-it\"}",
                "{\"actionType\": \"do-it\", \"inputs\": " + inputs + "}");
    }

    @Test
    void unmappedRequiredInputIsRejected() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW, json(withNodeInputs("{}")),
                withInputs());
        assertTrue(problems.stream().anyMatch(p -> p.isError() && p.code().equals("UNMAPPED_REQUIRED_INPUT")
                && p.path().equals("content.nodes[1].config.inputs")
                && p.message().contains("'ref'")), problems.toString());
    }

    @Test
    void mappedRequiredInputPassesWithOptionalUnmapped() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW,
                json(withNodeInputs("{\"ref\": \"context.ref\"}")), withInputs());
        assertFalse(ConfigItemValidationService.hasErrors(problems), problems.toString());
    }

    @Test
    void unknownInputMappingIsRejected() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW,
                json(withNodeInputs("{\"ref\": \"context.ref\", \"bogus\": \"1\"}")), withInputs());
        assertTrue(problems.stream().anyMatch(p -> p.isError() && p.code().equals("UNKNOWN_INPUT_MAPPING")
                && p.path().equals("content.nodes[1].config.inputs.bogus")), problems.toString());
    }

    @Test
    void blankRequiredInputMappingIsRejected() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW,
                json(withNodeInputs("{\"ref\": \"  \"}")), withInputs());
        assertTrue(problems.stream().anyMatch(p -> p.isError() && p.code().equals("EMPTY_INPUT_MAPPING")
                && p.path().equals("content.nodes[1].config.inputs.ref")), problems.toString());
    }

    @Test
    void invalidElInputMappingIsRejected() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW,
                json(withNodeInputs("{\"ref\": \"context.ref ==\"}")), withInputs());
        assertTrue(problems.stream().anyMatch(p -> p.isError() && p.code().equals("INVALID_INPUT_EXPRESSION")
                && p.path().equals("content.nodes[1].config.inputs.ref")), problems.toString());
    }

    @Test
    void unknownContextKeyInRequiredMappingIsRejected() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW,
                json(withNodeInputs("{\"ref\": \"context.issueUrl\"}")), withInputs());
        assertTrue(problems.stream().anyMatch(p -> p.isError() && p.code().equals("UNKNOWN_CONTEXT_KEY")
                && p.path().equals("content.nodes[1].config.inputs.ref")
                && p.message().contains("issueUrl")), problems.toString());
    }

    @Test
    void unknownContextKeyInOptionalMappingWarns() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW,
                json(withNodeInputs("{\"ref\": \"context.ref\", \"note\": \"context.nothing\"}")), withInputs());
        assertFalse(ConfigItemValidationService.hasErrors(problems), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> !p.isError() && p.code().equals("UNKNOWN_CONTEXT_KEY")
                && p.path().equals("content.nodes[1].config.inputs.note")), problems.toString());
    }

    @Test
    void contextKeysFromStartInputsAreKnown() throws Exception {
        String wf = withNodeInputs("{\"ref\": \"context.ref\", \"note\": \"context.priority\"}")
                .replace("{\"id\": \"s\", \"type\": \"start\", \"name\": \"Start\", \"config\": {}",
                        "{\"id\": \"s\", \"type\": \"start\", \"name\": \"Start\", "
                                + "\"config\": {\"inputs\": [{\"name\": \"priority\", \"type\": \"string\"}]}");
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW, json(wf), withInputs());
        assertTrue(problems.stream().noneMatch(p -> p.code().equals("UNKNOWN_CONTEXT_KEY")), problems.toString());
    }

    @Test
    void bareEventReferenceInMappingIsRejected() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW,
                json(withNodeInputs("{\"ref\": \"event.ref\"}")), withInputs());
        assertTrue(problems.stream().anyMatch(p -> p.isError() && p.code().equals("BARE_EVENT_REFERENCE")
                && p.message().contains("context.event")), problems.toString());
    }

    @Test
    void subscriptionMayReferenceWorkflowByName() throws Exception {
        ValidationContext ctx = new ValidationContext(Set.of(), Set.of(), Set.of(), Set.of("triage"),
                Set.of(), Set.of(), Set.of(), null);
        List<ConfigProblem> problems = service.validate(ConfigItemType.SUBSCRIPTION, json("""
            {"name": "s", "filterExpression": "event.type == 'issue.created'",
             "routing": [{"type": "create-workflow", "workflowDefinitionName": "triage"}]}
            """), ctx);
        assertFalse(ConfigItemValidationService.hasErrors(problems), problems.toString());
    }

    @Test
    void subscriptionWithUnknownWorkflowNameFails() throws Exception {
        ValidationContext ctx = new ValidationContext(Set.of(), Set.of(), Set.of(), Set.of("triage"),
                Set.of(), Set.of(), Set.of(), null);
        List<ConfigProblem> problems = service.validate(ConfigItemType.SUBSCRIPTION, json("""
            {"name": "s", "routing": [{"type": "create-workflow", "workflowDefinitionName": "nope"}]}
            """), ctx);
        assertTrue(problems.stream().anyMatch(p -> p.code().equals("UNKNOWN_WORKFLOW_DEFINITION")));
    }

    @Test
    void unknownEventTypeInFilterWarns() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.SUBSCRIPTION, json("""
            {"name": "s", "filterExpression": "event.type == 'issues'"}
            """), ValidationContext.unchecked());
        assertTrue(problems.stream().anyMatch(p -> !p.isError() && p.code().equals("UNKNOWN_EVENT_TYPE")));
    }
}
