package io.apitomy.axiom.agents.claudecode;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ExecutionLogBuilder}.
 */
class ExecutionLogBuilderTest {

    @Test
    void headerNamesTheTaskAndActionType() {
        ExecutionLogBuilder builder = new ExecutionLogBuilder();
        builder.header(42L, "shout", Instant.parse("2026-10-28T10:00:00Z"));
        assertTrue(builder.build().startsWith("=== Task 42 (shout) — Started 2026-10-28T10:00:00Z ==="),
                builder.build());
    }

    @Test
    void headerWithoutTaskSaysExecution() {
        ExecutionLogBuilder builder = new ExecutionLogBuilder();
        builder.header(null, null, Instant.parse("2026-10-28T10:00:00Z"));
        assertTrue(builder.build().startsWith("=== Execution — Started 2026-10-28T10:00:00Z ==="),
                builder.build());
    }
}
