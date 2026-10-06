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
}
