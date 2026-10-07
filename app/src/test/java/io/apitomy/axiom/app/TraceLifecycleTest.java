package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.core.tracing.TraceService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every unit of work ends with exactly one trace in a final state and that
 * no trace (or task trace node) is left {@code in-progress} once its work item is final.
 */
@QuarkusTest
class TraceLifecycleTest {

    @InjectMock
    AgentPool agentPool;

    @Inject
    TaskExecutionService taskExecutionService;

    @Inject
    TraceService traceService;

    @BeforeEach
    void noAgentsAvailable() {
        Mockito.when(agentPool.tryLease(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Optional.empty());
    }

    // ── Tasks ────────────────────────────────────────────────────────

    @Test
    void failedTaskClosesTaskNodeAndTrace() {
        Long taskId = createTaskWithTrace();
        UUID traceId = traceIdOf(taskId);

        taskExecutionService.failTask(taskId, "boom");

        assertEquals("Failed", QuarkusTransaction.requiringNew().call(() ->
                TaskEntity.<TaskEntity>findById(taskId).status));
        assertTaskNodeStatus(traceId, taskId, "failed");
        assertTraceFinal(traceId, "failed");
    }

    @Test
    void completedTaskClosesTraceOnlyAfterLastTaskNode() {
        // Two tasks sharing one trace (as a single routing can produce)
        Long subjectId = createSubject();
        TraceContext ctx = traceService.createTrace("create-workflow", "multi-task", null, null,
                "create-workflow", "root", null, null);
        Long first = createTask(subjectId, ctx);
        Long second = createTask(subjectId, ctx);

        taskExecutionService.onTaskCompleted(first, AgentResult.success("ok"));
        assertEquals("in-progress", traceStatus(ctx.traceId()),
                "Trace must stay open while another task node is in progress");

        taskExecutionService.onTaskCompleted(second, AgentResult.success("ok"));
        assertTraceFinal(ctx.traceId(), "completed");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private Long createSubject() {
        return QuarkusTransaction.requiringNew().call(() -> {
            SubjectEntity subject = new SubjectEntity();
            subject.name = "trace-lifecycle-" + UUID.randomUUID();
            subject.type = "test-trace";
            subject.ref = "https://example.com/" + UUID.randomUUID();
            subject.createdOn = Instant.now();
            subject.updatedOn = Instant.now();
            subject.persist();
            return subject.id;
        });
    }

    private Long createTaskWithTrace() {
        Long subjectId = createSubject();
        TraceContext ctx = traceService.createTrace("create-workflow", "task trace", null, null,
                "create-workflow", "root", null, null);
        return createTask(subjectId, ctx);
    }

    private Long createTask(Long subjectId, TraceContext ctx) {
        Long taskId = QuarkusTransaction.requiringNew().call(() -> {
            TaskEntity task = new TaskEntity();
            task.subjectId = subjectId;
            task.actionType = "trace-lifecycle-action";
            task.createdBy = "test";
            task.status = "InProgress";
            task.createdOn = Instant.now();
            task.traceId = ctx.traceId();
            task.persist();
            return task.id;
        });
        traceService.addNode(ctx, "task", "in-progress", "Task", "task", taskId);
        return taskId;
    }

    private UUID traceIdOf(Long taskId) {
        return QuarkusTransaction.requiringNew().call(() ->
                TaskEntity.<TaskEntity>findById(taskId).traceId);
    }

    private String traceStatus(UUID traceId) {
        return QuarkusTransaction.requiringNew().call(() ->
                TraceEntity.<TraceEntity>findById(traceId).status);
    }

    private void assertTaskNodeStatus(UUID traceId, Long taskId, String expected) {
        String status = QuarkusTransaction.requiringNew().call(() ->
                TraceNodeEntity.<TraceNodeEntity>find(
                        "traceId = ?1 and nodeType = 'task' and entityId = ?2", traceId,
                        String.valueOf(taskId))
                        .firstResult().status);
        assertEquals(expected, status);
    }

    private void assertTraceFinal(UUID traceId, String expected) {
        TraceEntity trace = QuarkusTransaction.requiringNew().call(() ->
                TraceEntity.<TraceEntity>findById(traceId));
        assertNotNull(trace);
        assertEquals(expected, trace.status);
        assertNotNull(trace.completedOn);
        List<TraceNodeEntity> open = QuarkusTransaction.requiringNew().call(() ->
                TraceNodeEntity.<TraceNodeEntity>list(
                        "traceId = ?1 and status = 'in-progress'", traceId));
        assertTrue(open.isEmpty(), "No trace node may remain in-progress: " + open.size());
    }
}
