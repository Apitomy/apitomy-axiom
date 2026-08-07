package io.apitomy.axiom.core.filters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventFilterEvaluatorTest {

    private final EventFilterEvaluator evaluator = new EventFilterEvaluator();
    private final ObjectMapper mapper = new ObjectMapper();

    // --- Include rules ---

    @Test
    void emptyIncludeAllowsAllEventTypes() {
        EventSourceFilters filters = new EventSourceFilters(List.of(), List.of());
        FilterResult result = evaluator.evaluate(filters, "issue-created", mapper.createObjectNode());
        assertTrue(result.allowed());
    }

    @Test
    void includeEventTypeAllowsMatchingEvent() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("event-type", null, "issue-*")),
                List.of());
        FilterResult result = evaluator.evaluate(filters, "issue-created", mapper.createObjectNode());
        assertTrue(result.allowed());
    }

    @Test
    void includeEventTypeBlocksNonMatchingEvent() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("event-type", null, "issue-*")),
                List.of());
        FilterResult result = evaluator.evaluate(filters, "pr-created", mapper.createObjectNode());
        assertFalse(result.allowed());
        assertNotNull(result.matchedRule());
    }

    @Test
    void includePayloadRuleAllowsMatchingPayload() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("payload", "/action", "opened")),
                List.of());
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "opened");
        FilterResult result = evaluator.evaluate(filters, "issue-created", payload);
        assertTrue(result.allowed());
    }

    @Test
    void includePayloadRuleBlocksNonMatchingPayload() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("payload", "/action", "opened")),
                List.of());
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "closed");
        FilterResult result = evaluator.evaluate(filters, "issue-created", payload);
        assertFalse(result.allowed());
    }

    // --- Exclude rules ---

    @Test
    void excludeEventTypeBlocksMatchingEvent() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(),
                List.of(new EventSourceFilterRule("event-type", null, "comment-added")));
        FilterResult result = evaluator.evaluate(filters, "comment-added", mapper.createObjectNode());
        assertFalse(result.allowed());
        assertTrue(result.matchedRule().contains("comment-added"));
    }

    @Test
    void excludePayloadRuleBlocksBotLogin() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(),
                List.of(new EventSourceFilterRule("payload", "/user/login", "*[bot]")));
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("user").put("login", "dependabot[bot]");
        FilterResult result = evaluator.evaluate(filters, "issue-created", payload);
        assertFalse(result.allowed());
        assertTrue(result.matchedRule().contains("/user/login"));
    }

    @Test
    void excludePayloadRuleAllowsNonMatchingValue() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(),
                List.of(new EventSourceFilterRule("payload", "/user/login", "*[bot]")));
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("user").put("login", "octocat");
        FilterResult result = evaluator.evaluate(filters, "issue-created", payload);
        assertTrue(result.allowed());
    }

    @Test
    void excludeSlashCommandBlocksCommentStartingWithSlash() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(),
                List.of(new EventSourceFilterRule("payload", "/comment/body", "/*")));
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("comment").put("body", "/ready");
        FilterResult result = evaluator.evaluate(filters, "comment-added", payload);
        assertFalse(result.allowed());
    }

    // --- Combined include + exclude ---

    @Test
    void excludeOverridesInclude() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("event-type", null, "issue-*")),
                List.of(new EventSourceFilterRule("payload", "/user/login", "*[bot]")));
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("user").put("login", "dependabot[bot]");
        FilterResult result = evaluator.evaluate(filters, "issue-created", payload);
        assertFalse(result.allowed());
    }

    // --- Edge cases ---

    @Test
    void missingPointerFieldDoesNotMatch() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(),
                List.of(new EventSourceFilterRule("payload", "/nonexistent/field", "*")));
        FilterResult result = evaluator.evaluate(filters, "issue-created", mapper.createObjectNode());
        assertTrue(result.allowed());
    }

    @Test
    void nullFiltersAllowsAllEvents() {
        FilterResult result = evaluator.evaluate(null, "issue-created", mapper.createObjectNode());
        assertTrue(result.allowed());
    }

    @Test
    void nullPayloadWithPayloadRuleDoesNotMatch() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(),
                List.of(new EventSourceFilterRule("payload", "/user/login", "*")));
        FilterResult result = evaluator.evaluate(filters, "issue-created", null);
        assertTrue(result.allowed());
    }

    @Test
    void questionMarkWildcardMatchesSingleCharacter() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("event-type", null, "pr-c????d")),
                List.of());
        assertTrue(evaluator.evaluate(filters, "pr-closed", mapper.createObjectNode()).allowed());
        assertFalse(evaluator.evaluate(filters, "pr-created", mapper.createObjectNode()).allowed());
    }

    @Test
    void exactMatchWithNoWildcards() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("event-type", null, "pr-merged")),
                List.of());
        assertTrue(evaluator.evaluate(filters, "pr-merged", mapper.createObjectNode()).allowed());
        assertFalse(evaluator.evaluate(filters, "pr-created", mapper.createObjectNode()).allowed());
    }

    @Test
    void multipleIncludeRulesAnyCanMatch() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(
                        new EventSourceFilterRule("event-type", null, "issue-created"),
                        new EventSourceFilterRule("event-type", null, "pr-created")),
                List.of());
        assertTrue(evaluator.evaluate(filters, "issue-created", mapper.createObjectNode()).allowed());
        assertTrue(evaluator.evaluate(filters, "pr-created", mapper.createObjectNode()).allowed());
        assertFalse(evaluator.evaluate(filters, "comment-added", mapper.createObjectNode()).allowed());
    }
}
