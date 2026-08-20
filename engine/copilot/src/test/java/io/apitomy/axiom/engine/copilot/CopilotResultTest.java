package io.apitomy.axiom.engine.copilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for CopilotResult parsing and construction.
 */
class CopilotResultTest {

    @Test
    void testSuccessfulResult() {
        CopilotResult result = new CopilotResult(
                "Analysis complete", "session-123", null, null, 800L, 0, null);

        assertTrue(result.isSuccess());
        assertEquals("Analysis complete", result.result());
        assertEquals("session-123", result.sessionId());
        assertNull(result.costUsd());
        assertEquals(800L, result.outputTokens());
        assertEquals(0, result.exitCode());
    }

    @Test
    void testFailedResult() {
        CopilotResult result = CopilotResult.failed("Process crashed", 1);

        assertFalse(result.isSuccess());
        assertEquals("Process crashed", result.result());
        assertNull(result.sessionId());
        assertNull(result.costUsd());
        assertEquals(1, result.exitCode());
    }

    @Test
    void testTimeoutResult() {
        CopilotResult result = CopilotResult.failed("Timed out", 124);

        assertFalse(result.isSuccess());
        assertEquals(124, result.exitCode());
    }
}
