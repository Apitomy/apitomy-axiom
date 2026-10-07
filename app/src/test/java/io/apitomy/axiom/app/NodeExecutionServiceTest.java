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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            task.input = "{\"labels\":[\"x\",\"y\"]}";
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
}
