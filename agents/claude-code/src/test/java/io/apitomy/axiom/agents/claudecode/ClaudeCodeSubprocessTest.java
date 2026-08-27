package io.apitomy.axiom.agents.claudecode;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ClaudeCodeSubprocess}.
 */
class ClaudeCodeSubprocessTest {

    /**
     * When the subprocess cannot be launched (e.g. the {@code claude} binary is
     * not on the PATH), the accumulated partial execution log must be preserved
     * on the returned result so it can be persisted and retrieved for
     * diagnostics. Regression test: previously the launch-failure path discarded
     * the log, leaving nothing to retrieve.
     */
    @Test
    void preservesPartialLogWhenLaunchFails() throws Exception {
        ExecutionLogBuilder logBuilder = new ExecutionLogBuilder();
        logBuilder.header(4802L, "test-action", Instant.now());
        logBuilder.prompt("do the thing");

        // A command that cannot be resolved forces ProcessBuilder.start() to
        // throw IOException, reproducing the "Cannot run program" failure.
        List<String> command = List.of(
                "this-binary-definitely-does-not-exist-xyz", "-p", "prompt");

        ClaudeCodeSubprocess subprocess = new ClaudeCodeSubprocess(
                command, null, Map.of(), Duration.ofSeconds(5), null, logBuilder);

        ClaudeCodeResult result = subprocess.execute().get();

        assertFalse(result.isSuccess(), "launch failure must not be reported as success");
        assertNotNull(result.executionLog(), "partial execution log must be preserved");
        assertTrue(result.executionLog().contains("=== Task 4802 (test-action)"),
                "log should retain the header written before launch");
        assertTrue(result.executionLog().contains("=== Command ==="),
                "log should retain the command section");
        assertTrue(result.executionLog().contains("=== Failed ==="),
                "log should include the failure footer");
    }
}
