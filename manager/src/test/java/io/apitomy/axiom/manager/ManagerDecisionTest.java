package io.apitomy.axiom.manager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the ManagerDecision record.
 */
class ManagerDecisionTest {

    @Test
    void testCreateTaskDecision() {
        ManagerDecision decision = new ManagerDecision(
                "create_task", "analyze", "claude-agent",
                "Analyze this issue", 0.9, "New issue needs analysis", null, null);

        assertTrue(decision.isCreateTask());
        assertFalse(decision.isIgnore());
        assertFalse(decision.isScriptAction());
        assertFalse(decision.isEscalate());
        assertEquals("analyze", decision.actionType());
        assertEquals("claude-agent", decision.actorHint());
        assertEquals(0.9, decision.confidence());
    }

    @Test
    void testIgnoreDecision() {
        ManagerDecision decision = new ManagerDecision(
                "ignore", null, null, null, 0.95, "Bot comment, ignoring", null, null);

        assertFalse(decision.isCreateTask());
        assertTrue(decision.isIgnore());
        assertNull(decision.actionType());
    }

    @Test
    void testScriptActionDecision() {
        ManagerDecision decision = new ManagerDecision(
                "script_action", "close-project", null, null, 0.85, "Issue closed", null, null);

        assertTrue(decision.isScriptAction());
        assertEquals("close-project", decision.actionType());
    }

    @Test
    void testEscalateDecision() {
        ManagerDecision decision = new ManagerDecision(
                "escalate", null, null, null, 0.3, "Uncertain what to do", null, null);

        assertTrue(decision.isEscalate());
        assertEquals(0.3, decision.confidence());
    }
}
