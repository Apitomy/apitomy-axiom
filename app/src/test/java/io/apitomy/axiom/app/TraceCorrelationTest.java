package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.core.tracing.TraceService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that activity log and AI usage rows carry the trace ID of the unit of work
 * that wrote them, so the trace ID can serve as a correlation ID (#427).
 */
@QuarkusTest
class TraceCorrelationTest {

    private static final String START_END_CONTENT = """
        {
            "id": "trace-corr-wf",
            "name": "Trace Correlation WF",
            "nodes": [
                {"id": "s1", "type": "start", "name": "Start",
                 "config": {}, "position": {"x": 100, "y": 100}},
                {"id": "e1", "type": "end", "name": "End",
                 "config": {}, "position": {"x": 100, "y": 200}}
            ],
            "edges": [
                {"id": "edge1", "source": "s1", "target": "e1",
                 "priority": 0, "isDefault": true}
            ]
        }
        """;

    @InjectMock
    AgentPool agentPool;

    @Inject
    TaskExecutionService taskExecutionService;

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Inject
    TraceService traceService;

    @BeforeEach
    void noAgentsAvailable() {
        Mockito.when(agentPool.tryLease(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void cleanupEventStream() {
        QuarkusTransaction.requiringNew().run(() -> {
            RoutingOutcomeItemEntity.deleteAll();
            RoutingOutcomeEntity.deleteAll();
            EventProcessingLedgerEntity.deleteAll();
            StreamEventEntity.deleteAll();
            EventSubscriptionEntity.deleteAll();
        });
    }

    // ── Tasks ────────────────────────────────────────────────────────

    @Test
    void taskActivityAndAiUsageCarryTaskTrace() {
        Long projectId = createProject();
        TraceContext ctx = traceService.createTrace("create-workflow", "corr task", null, null,
                "create-workflow", "root", null, null);
        Long taskId = QuarkusTransaction.requiringNew().call(() -> {
            TaskEntity task = new TaskEntity();
            task.projectId = projectId;
            task.actionType = "trace-correlation-action";
            task.createdBy = "test";
            task.status = "InProgress";
            task.createdOn = Instant.now();
            task.traceId = ctx.traceId();
            task.persist();
            return task.id;
        });
        traceService.addNode(ctx, "task", "in-progress", "Task", "task", taskId);

        Instant start = Instant.now();
        taskExecutionService.onTaskCompleted(taskId, AgentResult.success("ok"));

        // Scope to rows written here: other tests insert fixture rows with hard-coded task IDs
        List<ActivityLogEntity> rows = QuarkusTransaction.requiringNew().call(() ->
                ActivityLogEntity.<ActivityLogEntity>list("taskId = ?1 and createdOn >= ?2",
                        taskId, start));
        assertFalse(rows.isEmpty(), "Task completion must write an activity row");
        rows.forEach(r -> assertEquals(ctx.traceId(), r.traceId,
                "Activity row " + r.entryType + " must carry the task trace"));

        List<AiUsageEntity> usage = QuarkusTransaction.requiringNew().call(() ->
                AiUsageEntity.<AiUsageEntity>list("taskId", taskId));
        assertEquals(1, usage.size());
        assertEquals(ctx.traceId(), usage.get(0).traceId);
    }

    // ── Workflows ────────────────────────────────────────────────────

    @Test
    void workflowStartedActivityCarriesRunTrace() {
        Long projectId = createProject();
        Long defId = QuarkusTransaction.requiringNew().call(() -> {
            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = "corr-wf-" + UUID.randomUUID();
            def.content = START_END_CONTENT;
            def.currentVersion = 1;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();
            WorkflowDefinitionVersionEntity version = new WorkflowDefinitionVersionEntity();
            version.definitionId = def.id;
            version.version = 1;
            version.content = START_END_CONTENT;
            version.createdOn = Instant.now();
            version.persist();
            return def.id;
        });

        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(projectId, defId));
        assertNotNull(run.traceId);

        ActivityLogEntity row = QuarkusTransaction.requiringNew().call(() ->
                ActivityLogEntity.<ActivityLogEntity>find(
                        "projectId = ?1 and entryType = 'workflow-started'", projectId)
                        .firstResult());
        assertNotNull(row);
        assertEquals(run.traceId, row.traceId);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private Long createProject() {
        return QuarkusTransaction.requiringNew().call(() -> {
            ProjectEntity project = new ProjectEntity();
            project.name = "trace-correlation-" + UUID.randomUUID();
            project.type = "test-trace";
            project.status = "Idle";
            project.ref = "https://example.com/" + UUID.randomUUID();
            project.createdOn = Instant.now();
            project.updatedOn = Instant.now();
            project.persist();
            return project.id;
        });
    }
}
