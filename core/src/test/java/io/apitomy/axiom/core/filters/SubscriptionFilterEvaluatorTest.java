package io.apitomy.axiom.core.filters;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionFilterEvaluatorTest {

    private final SubscriptionFilterEvaluator evaluator = new SubscriptionFilterEvaluator();

    private Map<String, Object> makeEventMap(String type, String connectionId) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", type);
        map.put("connectionId", connectionId);
        map.put("source", "github");
        map.put("ref", "https://github.com/org/repo/issues/1");
        map.put("timestamp", "2026-09-21T10:00:00Z");
        map.put("actor", Map.of("login", "octocat"));
        map.put("payload", Map.of());
        return map;
    }

    // --- Null / blank expression matches all ---

    @Test
    void nullExpressionMatchesAll() {
        assertTrue(evaluator.matches(null, makeEventMap("issue.created", "github-com")));
    }

    @Test
    void blankExpressionMatchesAll() {
        assertTrue(evaluator.matches("", makeEventMap("issue.created", "github-com")));
    }

    // --- Simple type match ---

    @Test
    void simpleTypeMatch() {
        assertTrue(evaluator.matches(
                "event.type == 'issue.created'",
                makeEventMap("issue.created", "github-com")));
    }

    @Test
    void simpleTypeMismatch() {
        assertFalse(evaluator.matches(
                "event.type == 'issue.created'",
                makeEventMap("pr.merged", "github-com")));
    }

    // --- Wildcard with startsWith ---

    @Test
    void startsWithMatch() {
        assertTrue(evaluator.matches(
                "event.type.startsWith('issue.')",
                makeEventMap("issue.created", "github-com")));
    }

    // --- Connection match ---

    @Test
    void connectionMatch() {
        assertTrue(evaluator.matches(
                "event.connectionId == 'github-com'",
                makeEventMap("issue.created", "github-com")));
    }

    // --- AND logic ---

    @Test
    void andLogicBothMatch() {
        assertTrue(evaluator.matches(
                "event.type == 'issue.created' && event.connectionId == 'github-com'",
                makeEventMap("issue.created", "github-com")));
    }

    @Test
    void andLogicOneFails() {
        assertFalse(evaluator.matches(
                "event.type == 'issue.created' && event.connectionId == 'jira-prod'",
                makeEventMap("issue.created", "github-com")));
    }

    // --- OR logic ---

    @Test
    void orLogicMatchesEither() {
        assertTrue(evaluator.matches(
                "event.type == 'issue.created' || event.type == 'pr.created'",
                makeEventMap("issue.created", "github-com")));
        assertTrue(evaluator.matches(
                "event.type == 'issue.created' || event.type == 'pr.created'",
                makeEventMap("pr.created", "github-com")));
    }

    // --- NOT logic ---

    @Test
    void notLogicExcludes() {
        assertTrue(evaluator.matches(
                "event.connectionId != 'jira-staging'",
                makeEventMap("issue.created", "github-com")));
        assertFalse(evaluator.matches(
                "event.connectionId != 'github-com'",
                makeEventMap("issue.created", "github-com")));
    }

    // --- Payload field access ---

    @Test
    void payloadFieldAccess() {
        Map<String, Object> eventMap = makeEventMap("issue.updated", "github-com");
        eventMap.put("payload", Map.of("issue", Map.of("state", "open")));
        assertTrue(evaluator.matches(
                "event.payload.issue.state == 'open'",
                eventMap));
    }

    // --- Invalid expression fails closed ---

    @Test
    void invalidExpressionFailsClosed() {
        assertFalse(evaluator.matches(
                "invalid {{[ syntax",
                makeEventMap("issue.created", "github-com")));
    }

    // --- isValid ---

    @Test
    void isValidReturnsTrueForValidExpression() {
        assertTrue(evaluator.isValid("event.type == 'test'"));
    }

    @Test
    void isValidReturnsFalseForInvalidExpression() {
        assertFalse(evaluator.isValid("event.type ==== 'test'"));
    }
}
