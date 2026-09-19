package io.apitomy.axiom.core.filters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionFilterEvaluatorTest {

    private final SubscriptionFilterEvaluator evaluator = new SubscriptionFilterEvaluator(new ObjectMapper());
    private final ObjectMapper mapper = new ObjectMapper();

    // --- Null filters ---

    @Test
    void nullFiltersAllowsAll() {
        FilterResult result = evaluator.evaluate(null, "issue.created", "github-com",
                "https://github.com/EricWittmann/project", mapper.createObjectNode());
        assertTrue(result.allowed());
    }

    // --- Include event-type ---

    @Test
    void includeEventTypeAllowsMatching() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("event-type", null, "issue.*")),
                List.of());
        FilterResult result = evaluator.evaluate(filters, "issue.created", "github-com",
                "https://github.com/org/repo", mapper.createObjectNode());
        assertTrue(result.allowed());
    }

    @Test
    void includeEventTypeBlocksNonMatching() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("event-type", null, "issue.*")),
                List.of());
        FilterResult result = evaluator.evaluate(filters, "pr.created", "github-com",
                "https://github.com/org/repo", mapper.createObjectNode());
        assertFalse(result.allowed());
        assertNotNull(result.matchedRule());
    }

    // --- Exclude event-type ---

    @Test
    void excludeEventTypeBlocks() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(),
                List.of(new EventSourceFilterRule("event-type", null, "push")));
        FilterResult result = evaluator.evaluate(filters, "push", "github-com",
                "https://github.com/org/repo", mapper.createObjectNode());
        assertFalse(result.allowed());
        assertTrue(result.matchedRule().contains("push"));
    }

    // --- Exclude overrides include ---

    @Test
    void excludeOverridesInclude() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("event-type", null, "issue.*")),
                List.of(new EventSourceFilterRule("event-type", null, "issue.comment.*")));
        FilterResult result = evaluator.evaluate(filters, "issue.comment.created", "github-com",
                "https://github.com/org/repo", mapper.createObjectNode());
        assertFalse(result.allowed());
    }

    // --- Connection filter ---

    @Test
    void connectionFilterAllowsMatching() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("connection", null, "github-*")),
                List.of());
        FilterResult result = evaluator.evaluate(filters, "issue.created", "github-com",
                "https://github.com/org/repo", mapper.createObjectNode());
        assertTrue(result.allowed());
    }

    @Test
    void connectionFilterBlocksNonMatching() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("connection", null, "github-*")),
                List.of());
        FilterResult result = evaluator.evaluate(filters, "issue.created", "jira-prod",
                "https://jira.example.com/browse/PROJ-1", mapper.createObjectNode());
        assertFalse(result.allowed());
    }

    // --- Ref filter ---

    @Test
    void refFilterMatchesContainingString() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("ref", null, "*EricWittmann*")),
                List.of());
        FilterResult result = evaluator.evaluate(filters, "issue.created", "github-com",
                "https://github.com/EricWittmann/project", mapper.createObjectNode());
        assertTrue(result.allowed());
    }

    @Test
    void refFilterBlocksNonMatching() {
        EventSourceFilters filters = new EventSourceFilters(
                List.of(new EventSourceFilterRule("ref", null, "*EricWittmann*")),
                List.of());
        FilterResult result = evaluator.evaluate(filters, "issue.created", "github-com",
                "https://github.com/other-org/project", mapper.createObjectNode());
        assertFalse(result.allowed());
    }

    // --- Payload filter ---

    @Test
    void payloadExcludeBlocksMatchingValue() {
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("issue").put("state", "closed");
        EventSourceFilters filters = new EventSourceFilters(
                List.of(),
                List.of(new EventSourceFilterRule("payload", "/issue/state", "closed")));
        FilterResult result = evaluator.evaluate(filters, "issue.updated", "github-com",
                "https://github.com/org/repo", payload);
        assertFalse(result.allowed());
        assertTrue(result.matchedRule().contains("/issue/state"));
    }

    @Test
    void payloadExcludeAllowsNonMatchingValue() {
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("issue").put("state", "open");
        EventSourceFilters filters = new EventSourceFilters(
                List.of(),
                List.of(new EventSourceFilterRule("payload", "/issue/state", "closed")));
        FilterResult result = evaluator.evaluate(filters, "issue.updated", "github-com",
                "https://github.com/org/repo", payload);
        assertTrue(result.allowed());
    }

    // --- Empty include allows all ---

    @Test
    void emptyIncludeAllowsAll() {
        EventSourceFilters filters = new EventSourceFilters(List.of(), List.of());
        FilterResult result = evaluator.evaluate(filters, "anything.here", "some-connection",
                "https://example.com/ref", mapper.createObjectNode());
        assertTrue(result.allowed());
    }
}
