package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.flow.spi.NodeResult;
import io.apitomy.flow.spi.NodeResultStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that agent failures are recorded on the task and propagated to the workflow node result.
 */
class TaskFailureDetailTest {

    @Test
    void failureWithoutOutputRecordsErrorAsOutputAndLog() {
        TaskEntity task = new TaskEntity();
        TaskExecutionService.applyFailureDetail(task, "OpenCode error: HTTP 500 — UnknownError: ENOENT (ref: e1)");
        assertEquals("OpenCode error: HTTP 500 — UnknownError: ENOENT (ref: e1)", task.output);
        assertTrue(task.executionLog.contains("ENOENT (ref: e1)"));
    }

    @Test
    void failureDoesNotDuplicateErrorAlreadyInLogOrOverwriteOutput() {
        TaskEntity task = new TaskEntity();
        task.output = "partial";
        task.executionLog = "OpenCode error: boom\nOpenCode session: ses_1";
        TaskExecutionService.applyFailureDetail(task, "OpenCode error: boom");
        assertEquals("partial", task.output);
        assertEquals("OpenCode error: boom\nOpenCode session: ses_1", task.executionLog);
    }

    @Test
    void failureWithoutMessageStillExplains() {
        TaskEntity task = new TaskEntity();
        TaskExecutionService.applyFailureDetail(task, null);
        assertTrue(task.output.contains("without an error message"));
    }

    @Test
    void failedNodeResultCarriesTaskError() {
        TaskEntity task = new TaskEntity();
        task.output = "OpenCode error: boom";
        NodeResult result = WorkflowExecutionService.failedNodeResult(task);
        assertEquals(NodeResultStatus.FAILED, result.status());
        assertEquals("OpenCode error: boom", result.output().get("error"));
    }

    @Test
    void failedNodeResultFallsBackToGenericReason() {
        TaskEntity task = new TaskEntity();
        task.id = 7L;
        NodeResult result = WorkflowExecutionService.failedNodeResult(task);
        assertEquals("Task 7 failed", result.output().get("error"));
    }
}
