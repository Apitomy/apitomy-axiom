package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActionTypeField;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs node-mode action types end to end through {@link NodeExecutionService#runNode}.
 */
@QuarkusTest
class NodeExecutionServiceTest {

    @Inject
    NodeExecutionService nodeExecutionService;

    @Inject
    ObjectMapper objectMapper;

    @BeforeAll
    static void requireNode() {
        boolean available;
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            available = p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "node is not installed");
    }

    private ScriptExecutionService.RunResult run(String code) {
        return run(code, "{\"labels\":[\"x\",\"y\"]}");
    }

    private ScriptExecutionService.RunResult run(String code, String input) {
        String name = "node-exec-" + UUID.randomUUID();
        return QuarkusTransaction.requiringNew().call(() -> {
            ActionTypeEntity at = new ActionTypeEntity();
            at.name = name;
            at.executionMode = "node";
            at.codeTemplate = code;
            at.inputs = new ArrayList<>(List.of(new ActionTypeField("labels", "object", true, null)));
            at.outputs = new ArrayList<>(List.of(new ActionTypeField("count", "number", true, null)));
            at.persist();

            SubjectEntity subject = new SubjectEntity();
            subject.name = "node-exec";
            subject.type = "test";
            subject.ref = name;
            subject.createdOn = Instant.now();
            subject.updatedOn = Instant.now();
            subject.persist();

            TaskEntity task = new TaskEntity();
            task.subjectId = subject.id;
            task.actionType = name;
            task.createdBy = "test";
            task.status = "Pending";
            task.createdOn = Instant.now();
            task.workflowRunId = -1L;
            task.input = input;
            task.persist();

            return nodeExecutionService.runNode(task, subject);
        });
    }

    @Test
    void runsCodeWithDeclaredInputs() throws Exception {
        ScriptExecutionService.RunResult result =
                run("export async function run(inputs){ return { count: inputs.labels.length }; }\n");
        assertEquals(0, result.exitCode(), result.executionLog());
        assertEquals(objectMapper.readTree("{\"count\":2}"), objectMapper.readTree(result.output()));
        assertTrue(result.executionLog().contains("── Code ─"), result.executionLog());
    }

    @Test
    void thrownErrorFailsWithLog() {
        ScriptExecutionService.RunResult result =
                run("export async function run(){ throw new Error(\"nope\"); }\n");
        assertEquals(1, result.exitCode());
        assertTrue(result.executionLog().contains("Action failed: nope"), result.executionLog());
    }

    @Test
    void largeInputIsDelivered() throws Exception {
        String big = "a".repeat(1024 * 1024);
        ScriptExecutionService.RunResult result =
                run("export async function run(inputs){ return { count: inputs.labels[0].length }; }\n",
                        "{\"labels\":[\"" + big + "\"]}");
        assertEquals(0, result.exitCode(), result.executionLog());
        assertEquals(objectMapper.readTree("{\"count\":" + big.length() + "}"),
                objectMapper.readTree(result.output()));
        assertTrue(result.executionLog().contains("Node Execution Log"), result.executionLog());
    }

    @Test
    void nodeMissingOnlyWhenWorkingDirectoryExists(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        java.io.IOException notFound = new java.io.IOException(
                "Cannot run program \"node\": error=2, No such file or directory");
        assertTrue(NodeExecutionService.isNodeMissing(notFound, dir));
        assertFalse(NodeExecutionService.isNodeMissing(notFound, dir.resolve("gone")));
        assertFalse(NodeExecutionService.isNodeMissing(new java.io.IOException("error=13, Permission denied"), dir));
    }

    @Test
    void runsANodeActionWithoutATask() throws Exception {
        ActionTypeEntity at = new ActionTypeEntity();
        at.name = "draft-node";
        at.executionMode = "node";
        at.inputs = new ArrayList<>(List.of(new ActionTypeField("labels", "object", true, null)));
        at.codeTemplate = "export async function run(inputs, ctx) {\n"
                + "    ctx.log('counting');\n"
                + "    return { count: inputs.labels.length, extra: inputs.ignored ?? null };\n"
                + "}\n";
        ScriptExecutionService.ActionRun run = QuarkusTransaction.requiringNew().call(() ->
                nodeExecutionService.runNodeAction(at, Map.of("labels", List.of("x", "y"), "ignored", "z"),
                        null, "test-run"));
        assertEquals(0, run.exitCode());
        assertEquals("{\"count\":2,\"extra\":null}", run.output());
        assertTrue(run.stderr().contains("counting"));
        assertNull(run.resolvedScript());
    }
}
