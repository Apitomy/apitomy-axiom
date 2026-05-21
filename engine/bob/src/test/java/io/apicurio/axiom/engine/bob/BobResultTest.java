package io.apicurio.axiom.engine.bob;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BobResultTest {

    @Test
    void testSuccessfulResult() {
        BobResult result = new BobResult("Analysis complete", 0);

        assertTrue(result.isSuccess());
        assertEquals("Analysis complete", result.result());
        assertEquals(0, result.exitCode());
    }

    @Test
    void testFailedResult() {
        BobResult result = BobResult.failed("Process crashed", 1);

        assertFalse(result.isSuccess());
        assertEquals("Process crashed", result.result());
        assertEquals(1, result.exitCode());
    }

    @Test
    void testTimeoutResult() {
        BobResult result = BobResult.failed("Timed out", 124);

        assertFalse(result.isSuccess());
        assertEquals(124, result.exitCode());
    }
}
