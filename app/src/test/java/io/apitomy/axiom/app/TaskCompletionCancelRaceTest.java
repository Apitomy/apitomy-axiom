package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A workflow task's completion and the cancellation of its run lock the run row before the task row,
 * so they can neither deadlock nor overwrite each other.
 */
@QuarkusTest
class TaskCompletionCancelRaceTest {

    private static final String CANCEL_OUTPUT = "Cancelled: workflow was cancelled";

    @InjectMock
    AgentPool agentPool;

    @InjectMock
    McpConfigGenerator mcpConfigGenerator;

    @Inject
    TaskExecutionService taskExecutionService;

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Inject
    ScriptExecutionService scriptExecutionService;

    @Test
    void lateAgentResultDoesNotOverwriteCancelledTask() {
        long[] ids = createSubjectAndWaitingRun();
        Long taskId = createTask(ids[0], ids[1]);
        QuarkusTransaction.requiringNew().run(() -> workflowExecutionService.cancelWorkflowRun(ids[1]));

        taskExecutionService.onTaskCompleted(taskId, AgentResult.success("{\"late\":true}"));

        TaskEntity task = load(taskId);
        assertEquals("Failed", task.status);
        assertEquals(CANCEL_OUTPUT, task.output);
        assertEquals("cancelled", loadRun(ids[1]).status);
    }

    @Test
    void lateAgentFailureDoesNotOverwriteCancelledTask() {
        long[] ids = createSubjectAndWaitingRun();
        Long taskId = createTask(ids[0], ids[1]);
        QuarkusTransaction.requiringNew().run(() -> workflowExecutionService.cancelWorkflowRun(ids[1]));

        QuarkusTransaction.requiringNew().run(() -> taskExecutionService.failTask(taskId, "Unexpected error: boom"));

        assertEquals(CANCEL_OUTPUT, load(taskId).output);
    }

    @Test
    void lateScriptResultDoesNotOverwriteCancelledTask() {
        long[] ids = createSubjectAndWaitingRun();
        Long taskId = createTask(ids[0], ids[1]);
        QuarkusTransaction.requiringNew().run(() -> workflowExecutionService.cancelWorkflowRun(ids[1]));

        scriptExecutionService.completeTask(taskId, "{}", true, null);

        TaskEntity task = load(taskId);
        assertEquals("Failed", task.status);
        assertEquals(CANCEL_OUTPUT, task.output);
    }

    /**
     * The cancel transaction locks the run, then waits until the completion (on another thread) is
     * blocked on the database, then cancels. Before the fix the completion had already written the task
     * row, so the cancel's task update and the completion's run lock waited on each other.
     */
    @Test
    void concurrentCancelAndCompletionDoNotDeadlock() throws Exception {
        long[] ids = createSubjectAndWaitingRun();
        Long taskId = createTask(ids[0], ids[1]);
        CountDownLatch runLocked = new CountDownLatch(1);
        AtomicReference<Throwable> cancelError = new AtomicReference<>();

        CompletableFuture<Void> cancel = CompletableFuture.runAsync(() -> {
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    WorkflowRunEntity.findById(ids[1], LockModeType.PESSIMISTIC_WRITE);
                    runLocked.countDown();
                    awaitBlockedSession();
                    workflowExecutionService.cancelWorkflowRun(ids[1]);
                });
            } catch (Throwable t) {
                cancelError.set(t);
            }
        });
        assertTrue(runLocked.await(10, TimeUnit.SECONDS));
        AtomicReference<Throwable> completionError = new AtomicReference<>();
        CompletableFuture<Void> completion = CompletableFuture.runAsync(() -> {
            try {
                taskExecutionService.onTaskCompleted(taskId, AgentResult.success("{}"));
            } catch (Throwable t) {
                completionError.set(t);
            }
        });

        cancel.get(30, TimeUnit.SECONDS);
        completion.get(30, TimeUnit.SECONDS);

        assertNull(cancelError.get(), () -> "cancel failed: " + cancelError.get());
        assertNull(completionError.get(), () -> "completion failed: " + completionError.get());
        assertEquals("cancelled", loadRun(ids[1]).status);
        TaskEntity task = load(taskId);
        assertEquals("Failed", task.status);
        assertEquals(CANCEL_OUTPUT, task.output);
    }

    // -- Helpers --

    /** Polls H2 until some session is waiting for a lock (the completion thread). */
    private static void awaitBlockedSession() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Number blocked = (Number) TaskEntity.getEntityManager()
                    .createNativeQuery("select count(*) from information_schema.sessions where blocker_id is not null")
                    .getSingleResult();
            if (blocked.longValue() > 0) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new IllegalStateException("The completion never blocked on the run lock");
    }

    private static final String WAIT_CONTENT = """
        {
            "id": "race-wait-wf",
            "name": "Race Wait WF",
            "nodes": [
                {"id": "s1", "type": "start", "name": "Start", "config": {}, "position": {"x": 0, "y": 0}},
                {"id": "w1", "type": "wait", "name": "Wait", "config": {"duration": "PT5M"},
                 "position": {"x": 0, "y": 100}},
                {"id": "e1", "type": "end", "name": "End", "config": {}, "position": {"x": 0, "y": 200}}
            ],
            "edges": [
                {"id": "edge1", "source": "s1", "target": "w1", "priority": 0, "isDefault": true},
                {"id": "edge2", "source": "w1", "target": "e1", "priority": 0, "isDefault": true}
            ]
        }
        """;

    /** Creates a subject and a workflow run parked on a wait node: {subjectId, runId}. */
    private long[] createSubjectAndWaitingRun() {
        Long subjectId = QuarkusTransaction.requiringNew().call(() -> {
            SubjectEntity subject = new SubjectEntity();
            subject.name = "race-" + UUID.randomUUID();
            subject.type = "test-race";
            subject.ref = "https://example.com/" + UUID.randomUUID();
            subject.createdOn = Instant.now();
            subject.updatedOn = Instant.now();
            subject.persist();
            return subject.id;
        });
        Long definitionId = QuarkusTransaction.requiringNew().call(() -> {
            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = "race-wf-" + UUID.randomUUID();
            def.content = WAIT_CONTENT;
            def.currentVersion = 1;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();
            WorkflowDefinitionVersionEntity version = new WorkflowDefinitionVersionEntity();
            version.definitionId = def.id;
            version.version = 1;
            version.content = WAIT_CONTENT;
            version.createdOn = Instant.now();
            version.persist();
            return def.id;
        });
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, definitionId));
        assertEquals("waiting", run.status);
        return new long[] { subjectId, run.id };
    }

    private Long createTask(Long subjectId, Long runId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            TaskEntity task = new TaskEntity();
            task.subjectId = subjectId;
            task.actionType = "race-action";
            task.createdBy = "test";
            task.status = "InProgress";
            task.createdOn = Instant.now();
            task.workflowRunId = runId;
            task.nodeId = "w1";
            task.persist();
            return task.id;
        });
    }

    private WorkflowRunEntity loadRun(long runId) {
        return QuarkusTransaction.requiringNew().call(() -> WorkflowRunEntity.<WorkflowRunEntity>findById(runId));
    }

    private TaskEntity load(Long taskId) {
        return QuarkusTransaction.requiringNew().call(() -> TaskEntity.<TaskEntity>findById(taskId));
    }
}
