package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AI usage recorded for a task carries the task's workflow run and that run's workflow definition.
 */
@QuarkusTest
class AiUsageWorkflowRecordingTest {

    @InjectMock
    AgentPool agentPool;

    @InjectMock
    McpConfigGenerator mcpConfigGenerator;

    @Inject
    TaskExecutionService taskExecutionService;

    @Inject
    WorkflowExecutionService workflowExecutionService;

    /** A workflow task's usage row gets workflowRunId and workflowDefinitionId. */
    @Test
    void workflowTaskUsageCarriesRunAndDefinition() {
        long subjectId = TestFixtures.createSubject("ai-rec-" + UUID.randomUUID());
        long definitionId = TestFixtures.createWaitDefinition("ai-rec-wf-" + UUID.randomUUID());
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, definitionId));
        long taskId = createTask(subjectId, run.id);

        taskExecutionService.onTaskCompleted(taskId, usageResult());

        AiUsageEntity usage = usageFor(taskId);
        assertNotNull(usage);
        assertEquals(run.id, usage.workflowRunId);
        assertEquals(definitionId, usage.workflowDefinitionId);
    }

    /** A standalone task's usage row has no workflow columns. */
    @Test
    void standaloneTaskUsageHasNoWorkflow() {
        long subjectId = TestFixtures.createSubject("ai-rec-" + UUID.randomUUID());
        long taskId = createTask(subjectId, null);

        taskExecutionService.onTaskCompleted(taskId, usageResult());

        AiUsageEntity usage = usageFor(taskId);
        assertNotNull(usage);
        assertNull(usage.workflowRunId);
        assertNull(usage.workflowDefinitionId);
    }

    private static AgentResult usageResult() {
        return new AgentResult(true, "{}", null, null, null, 0.42, 100L, 50L, "claude-code", "test-model");
    }

    private static AiUsageEntity usageFor(long taskId) {
        return QuarkusTransaction.requiringNew().call(() ->
                AiUsageEntity.<AiUsageEntity>find("taskId", taskId).firstResult());
    }

    private static long createTask(long subjectId, Long runId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            TaskEntity task = new TaskEntity();
            task.subjectId = subjectId;
            task.actionType = "ai-rec-action";
            task.createdBy = "test";
            task.status = "InProgress";
            task.createdOn = Instant.now();
            task.workflowRunId = runId;
            task.nodeId = runId != null ? "w1" : null;
            task.persist();
            return task.id;
        });
    }
}
