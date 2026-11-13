package io.apitomy.axiom.agents.copilot;

import io.apitomy.axiom.agents.spi.AgentRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the Copilot CLI command line built from an agent request. */
class CopilotCommandBuilderTest {

    /** Collects the values of every occurrence of a repeated flag. */
    private static List<String> values(List<String> cmd, String flag) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < cmd.size() - 1; i++) {
            if (flag.equals(cmd.get(i))) {
                result.add(cmd.get(i + 1));
            }
        }
        return result;
    }

    /** A read-only request (like a run summary) passes every disallowed tool as a deny rule. */
    @Test
    void readOnlyRequestDeniesShellWriteAndUrlTools() {
        AgentRequest request = AgentRequest.builder()
                .prompt("summarize")
                .allowedTools(List.of(
                        "mcp__axiom-run-summary__get_run",
                        "mcp__axiom-run-summary__get_task",
                        "mcp__axiom-run-summary__get_task_log",
                        "mcp__axiom-run-summary__get_trace"))
                .disallowedTools(List.of("shell", "write", "url"))
                .build();
        List<String> cmd = CopilotCommandBuilder.fromRequest(request).build();
        assertEquals(List.of("shell", "write", "url"), values(cmd, "--deny-tool"));
        assertTrue(cmd.contains("--allow-all-tools"));
    }
}
