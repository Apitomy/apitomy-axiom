package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.ReportDefinitionEntity;
import io.apitomy.axiom.core.entities.ReportEntity;
import io.apitomy.axiom.core.entities.ScheduledJobEntity;
import io.apitomy.axiom.core.entities.ScheduledJobRunEntity;
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
    ScheduledJobExecutionService scheduledJobExecutionService;

    @Inject
    ReportExecutionService reportExecutionService;

    @Inject
    TaskExecutionService taskExecutionService;

    @Inject
    TraceService traceService;

    @BeforeEach
    void noAgentsAvailable() {
        Mockito.when(agentPool.tryLease(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Optional.empty());
    }

    // ── Scheduled jobs ───────────────────────────────────────────────

    @Test
    void scheduledJobWithNoAgentFailsRunAndClosesLinkedTrace() {
        String name = "trace-job-" + UUID.randomUUID();
        Long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            ScheduledJobEntity job = new ScheduledJobEntity();
            job.name = name;
            job.enabled = true;
            job.schedule = "daily";
            job.executionMode = "agent";
            job.promptTemplate = "Do something";
            job.createdOn = Instant.now();
            job.updatedOn = Instant.now();
            job.persist();
            ScheduledJobRunEntity run = new ScheduledJobRunEntity();
            run.jobId = job.id;
            run.status = "Pending";
            run.trigger = "manual";
            run.createdOn = Instant.now();
            run.persist();
            return new Long[] { job.id, run.id };
        });

        ScheduledJobEntity job = QuarkusTransaction.requiringNew().call(() ->
                ScheduledJobEntity.<ScheduledJobEntity>findById(ids[0]));
        scheduledJobExecutionService.executeRun(job, ids[1]);

        ScheduledJobRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                ScheduledJobRunEntity.<ScheduledJobRunEntity>findById(ids[1]));
        assertEquals("Failed", run.status);
        assertNotNull(run.traceId, "Run must stay linked to its trace");
        assertTraceFinal(run.traceId, "failed");
    }

    // ── Reports ──────────────────────────────────────────────────────

    @Test
    void reportWithNoAgentFailsReportAndClosesLinkedTrace() {
        Long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            ReportDefinitionEntity def = new ReportDefinitionEntity();
            def.name = "trace-report-" + UUID.randomUUID();
            def.schedule = "daily";
            def.promptTemplate = "Report on {{repositories}}";
            def.enabled = true;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();
            ReportEntity report = new ReportEntity();
            report.definitionId = def.id;
            report.status = "Pending";
            report.createdOn = Instant.now();
            report.persist();
            return new Long[] { def.id, report.id };
        });

        ReportDefinitionEntity def = QuarkusTransaction.requiringNew().call(() ->
                ReportDefinitionEntity.<ReportDefinitionEntity>findById(ids[0]));
        reportExecutionService.generateReport(def, ids[1]);

        ReportEntity report = QuarkusTransaction.requiringNew().call(() ->
                ReportEntity.<ReportEntity>findById(ids[1]));
        assertEquals("Failed", report.status, "Report must not stay Pending");
        assertNotNull(report.traceId, "Report must stay linked to its trace");
        assertTraceFinal(report.traceId, "failed");
        long traces = QuarkusTransaction.requiringNew().call(() ->
                TraceEntity.count("reportId", ids[1]));
        assertEquals(1, traces, "Exactly one trace per report attempt");
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
        // Two tasks sharing one trace (as a Manager evaluation can produce)
        Long projectId = createProject();
        TraceContext ctx = traceService.createTrace("manager", "multi-task", null, null, null,
                "manager-evaluation", "root", null, null);
        Long first = createTask(projectId, ctx);
        Long second = createTask(projectId, ctx);

        taskExecutionService.onTaskCompleted(first, AgentResult.success("ok"));
        assertEquals("in-progress", traceStatus(ctx.traceId()),
                "Trace must stay open while another task node is in progress");

        taskExecutionService.onTaskCompleted(second, AgentResult.success("ok"));
        assertTraceFinal(ctx.traceId(), "completed");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private Long createProject() {
        return QuarkusTransaction.requiringNew().call(() -> {
            ProjectEntity project = new ProjectEntity();
            project.name = "trace-lifecycle-" + UUID.randomUUID();
            project.type = "test-trace";
            project.status = "Idle";
            project.ref = "https://example.com/" + UUID.randomUUID();
            project.createdOn = Instant.now();
            project.updatedOn = Instant.now();
            project.persist();
            return project.id;
        });
    }

    private Long createTaskWithTrace() {
        Long projectId = createProject();
        TraceContext ctx = traceService.createTrace("invoke-action", "task trace", null, null, null,
                "invoke-action", "root", null, null);
        return createTask(projectId, ctx);
    }

    private Long createTask(Long projectId, TraceContext ctx) {
        Long taskId = QuarkusTransaction.requiringNew().call(() -> {
            TaskEntity task = new TaskEntity();
            task.projectId = projectId;
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
