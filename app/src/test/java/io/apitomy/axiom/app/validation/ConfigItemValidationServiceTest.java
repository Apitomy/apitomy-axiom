package io.apitomy.axiom.app.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.filters.SubscriptionFilterEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests for {@link ConfigItemValidationService} (no Quarkus, no database).
 */
class ConfigItemValidationServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
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
        return new ValidationContext(tools, toolsets, Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
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
            {"name": "triage", "executionMode": "agent", "promptTemplate": "Triage {{ref}}",
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
        return new ValidationContext(Set.of(), Set.of(), Set.of(names), Set.of(), Set.of(), Set.of(), Set.of());
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
        assertTrue(ConfigItemValidationService.hasErrors(problems), problems.toString());
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
}
