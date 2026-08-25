package io.apitomy.axiom.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ManagerService's decision parsing logic.
 * Uses reflection to inject the ObjectMapper and call the package-private parseDecisions method.
 */
class ManagerDecisionParsingTest {

    private ManagerService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new ManagerService();
        // Inject ObjectMapper via reflection (no CDI in unit tests)
        Field omField = ManagerService.class.getDeclaredField("objectMapper");
        omField.setAccessible(true);
        omField.set(service, new ObjectMapper());
    }

    @Test
    void testParseSingleDecision() {
        String json = """
                {
                    "decisions": [
                        {
                            "decision": "create_task",
                            "actionType": "analyze",
                            "agentHint": "claude-agent",
                            "inputContext": "Please analyze this new issue",
                            "confidence": 0.92,
                            "reasoning": "New issue detected, needs analysis"
                        }
                    ]
                }
                """;

        List<ManagerDecision> decisions = service.parseDecisions(json);

        assertEquals(1, decisions.size());
        ManagerDecision d = decisions.getFirst();
        assertTrue(d.isCreateTask());
        assertEquals("analyze", d.actionType());
        assertEquals("claude-agent", d.agentHint());
        assertEquals("Please analyze this new issue", d.inputContext());
        assertEquals(0.92, d.confidence(), 0.01);
        assertEquals("New issue detected, needs analysis", d.reasoning());
    }

    @Test
    void testParseMultipleDecisions() {
        String json = """
                {
                    "decisions": [
                        {
                            "decision": "create_task",
                            "actionType": "auto-tag",
                            "confidence": 0.95,
                            "reasoning": "New issue should be tagged"
                        },
                        {
                            "decision": "create_task",
                            "actionType": "analyze",
                            "confidence": 0.88,
                            "reasoning": "New issue needs analysis"
                        }
                    ]
                }
                """;

        List<ManagerDecision> decisions = service.parseDecisions(json);

        assertEquals(2, decisions.size());
        assertEquals("auto-tag", decisions.get(0).actionType());
        assertEquals("analyze", decisions.get(1).actionType());
    }

    @Test
    void testParseIgnoreDecision() {
        String json = """
                {
                    "decisions": [
                        {
                            "decision": "ignore",
                            "confidence": 0.98,
                            "reasoning": "Bot-generated comment, no action needed"
                        }
                    ]
                }
                """;

        List<ManagerDecision> decisions = service.parseDecisions(json);

        assertEquals(1, decisions.size());
        assertTrue(decisions.getFirst().isIgnore());
        assertNull(decisions.getFirst().actionType());
    }

    @Test
    void testParseScriptAction() {
        String json = """
                {
                    "decisions": [
                        {
                            "decision": "script_action",
                            "actionType": "close-project",
                            "confidence": 0.85,
                            "reasoning": "Issue has been closed"
                        }
                    ]
                }
                """;

        List<ManagerDecision> decisions = service.parseDecisions(json);

        assertEquals(1, decisions.size());
        assertTrue(decisions.getFirst().isScriptAction());
        assertEquals("close-project", decisions.getFirst().actionType());
    }

    @Test
    void testParseEscalation() {
        String json = """
                {
                    "decisions": [
                        {
                            "decision": "escalate",
                            "confidence": 0.3,
                            "reasoning": "Ambiguous event, needs human review"
                        }
                    ]
                }
                """;

        List<ManagerDecision> decisions = service.parseDecisions(json);

        assertEquals(1, decisions.size());
        assertTrue(decisions.getFirst().isEscalate());
        assertEquals(0.3, decisions.getFirst().confidence(), 0.01);
    }

    @Test
    void testParseNullInput() {
        List<ManagerDecision> decisions = service.parseDecisions(null);
        assertTrue(decisions.isEmpty());
    }

    @Test
    void testParseBlankInput() {
        List<ManagerDecision> decisions = service.parseDecisions("   ");
        assertTrue(decisions.isEmpty());
    }

    @Test
    void testParseInvalidJson() {
        List<ManagerDecision> decisions = service.parseDecisions("not json at all");
        assertTrue(decisions.isEmpty());
    }

    @Test
    void testParseMissingDecisionsArray() {
        String json = """
                { "something": "else" }
                """;

        List<ManagerDecision> decisions = service.parseDecisions(json);
        assertTrue(decisions.isEmpty());
    }

    @Test
    void testParseNestedResultField() {
        // Claude Code may wrap the structured output in a "result" field
        String json = """
                {
                    "result": "{\\"decisions\\":[{\\"decision\\":\\"ignore\\",\\"confidence\\":0.9,\\"reasoning\\":\\"test\\"}]}"
                }
                """;

        List<ManagerDecision> decisions = service.parseDecisions(json);

        assertEquals(1, decisions.size());
        assertTrue(decisions.getFirst().isIgnore());
    }

    @Test
    void testParseDefaultsForMissingFields() {
        String json = """
                {
                    "decisions": [
                        {
                            "confidence": 0.5,
                            "reasoning": "minimal"
                        }
                    ]
                }
                """;

        List<ManagerDecision> decisions = service.parseDecisions(json);

        assertEquals(1, decisions.size());
        ManagerDecision d = decisions.getFirst();
        assertEquals("ignore", d.decision()); // default
        assertNull(d.actionType());
        assertNull(d.agentHint());
        assertNull(d.inputContext());
    }

    @Test
    void testParseHumanContextAndOutputSchema() {
        String json = """
                {
                    "decisions": [
                        {
                            "decision": "create_task",
                            "actionType": "approve-deployment",
                            "agentHint": "human",
                            "inputContext": "PR #42 needs deployment approval",
                            "confidence": 0.95,
                            "reasoning": "Deployment requires human sign-off",
                            "humanContext": {
                                "title": "Approve deployment for auth-service",
                                "description": "PR #42 changes the authentication flow.",
                                "references": [
                                    { "label": "PR #42", "url": "https://github.com/org/repo/pull/42" }
                                ]
                            },
                            "outputSchema": {
                                "fields": [
                                    {
                                        "name": "approved",
                                        "type": "boolean",
                                        "label": "Approve?",
                                        "required": true
                                    },
                                    {
                                        "name": "strategy",
                                        "type": "select",
                                        "label": "Strategy",
                                        "required": true,
                                        "options": [
                                            { "label": "Blue-Green", "value": "blue-green" },
                                            { "label": "Canary", "value": "canary" }
                                        ]
                                    }
                                ]
                            }
                        }
                    ]
                }
                """;

        List<ManagerDecision> decisions = service.parseDecisions(json);

        assertEquals(1, decisions.size());
        ManagerDecision d = decisions.getFirst();
        assertTrue(d.isCreateTask());
        assertEquals("human", d.agentHint());

        // humanContext should be a JSON string
        assertNotNull(d.humanContext());
        assertTrue(d.humanContext().contains("Approve deployment for auth-service"));
        assertTrue(d.humanContext().contains("PR #42"));

        // outputSchema should be a JSON string
        assertNotNull(d.outputSchema());
        assertTrue(d.outputSchema().contains("approved"));
        assertTrue(d.outputSchema().contains("boolean"));
        assertTrue(d.outputSchema().contains("blue-green"));
    }

    @Test
    void testParseHumanTaskWithoutOptionalFields() {
        String json = """
                {
                    "decisions": [
                        {
                            "decision": "create_task",
                            "actionType": "review",
                            "agentHint": "human",
                            "inputContext": "Review this PR",
                            "confidence": 0.8,
                            "reasoning": "Needs human review"
                        }
                    ]
                }
                """;

        List<ManagerDecision> decisions = service.parseDecisions(json);

        assertEquals(1, decisions.size());
        ManagerDecision d = decisions.getFirst();
        assertNull(d.humanContext());
        assertNull(d.outputSchema());
    }
}
