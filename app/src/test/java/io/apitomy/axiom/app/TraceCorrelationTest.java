package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.ReportDefinitionEntity;
import io.apitomy.axiom.core.entities.ReportEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.ScheduledJobEntity;
import io.apitomy.axiom.core.entities.ScheduledJobRunEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.core.tracing.TraceService;
import io.apitomy.axiom.manager.ManagerDecision;
import io.apitomy.axiom.manager.ManagerEvaluationResult;
import io.apitomy.axiom.manager.ManagerService;
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

    @InjectMock
    ManagerService managerService;

    @Inject
    TaskExecutionService taskExecutionService;

    @Inject
    ReportExecutionService reportExecutionService;

    @Inject
    ScheduledJobExecutionService scheduledJobExecutionService;

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Inject
    EventStreamOrchestrator orchestrator;

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
        TraceContext ctx = traceService.createTrace("invoke-action", "corr task", null, null, null,
                "invoke-action", "root", null, null);
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

    // ── Reports ──────────────────────────────────────────────────────

    @Test
    void reportFailureActivityCarriesReportTrace() {
        Long[] ids = createReport();
        ReportDefinitionEntity def = QuarkusTransaction.requiringNew().call(() ->
                ReportDefinitionEntity.<ReportDefinitionEntity>findById(ids[0]));
        String defName = def.name;
        reportExecutionService.generateReport(def, ids[1]);

        ReportEntity report = QuarkusTransaction.requiringNew().call(() ->
                ReportEntity.<ReportEntity>findById(ids[1]));
        assertNotNull(report.traceId);
        ActivityLogEntity row = activityRow("report-failed", defName);
        assertEquals(report.traceId, row.traceId);
    }

    @Test
    void reportCompletionActivityAndAiUsageCarryReportTrace() {
        Long[] ids = createReport();
        String defName = QuarkusTransaction.requiringNew().call(() ->
                ReportDefinitionEntity.<ReportDefinitionEntity>findById(ids[0]).name);
        TraceContext ctx = traceService.createTrace("report", "corr report", null, null, null,
                "report", "root", "report", ids[1]);
        Instant start = Instant.now();

        reportExecutionService.markGenerating(ids[1], start, start, ctx.traceId());
        reportExecutionService.onReportCompleted(ids[1], ids[0], AgentResult.success("# Title"),
                ctx, null);

        assertEquals(ctx.traceId(), activityRow("report-generating", defName).traceId);
        assertEquals(ctx.traceId(), activityRow("report-completed", defName).traceId);
        List<AiUsageEntity> usage = QuarkusTransaction.requiringNew().call(() ->
                AiUsageEntity.<AiUsageEntity>list(
                        "invocationType = 'report' and createdOn >= ?1", start));
        assertFalse(usage.isEmpty());
        usage.forEach(u -> assertEquals(ctx.traceId(), u.traceId));
    }

    // ── Scheduled jobs ───────────────────────────────────────────────

    @Test
    void scheduledJobActivityAndAiUsageCarryRunTrace() {
        String name = "corr-job-" + UUID.randomUUID();
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
        TraceContext ctx = traceService.createTrace("scheduled-job-execution", "corr job",
                null, null, null, "scheduled-job", "root", null, null);
        Instant start = Instant.now();

        scheduledJobExecutionService.markRunning(ids[1], ctx.traceId());
        scheduledJobExecutionService.onAgentCompleted(ids[1], ids[0], AgentResult.success("ok"),
                ctx, null);

        assertEquals(ctx.traceId(), activityRow("scheduled-job-running", name).traceId);
        assertEquals(ctx.traceId(), activityRow("scheduled-job-completed", name).traceId);
        List<AiUsageEntity> usage = QuarkusTransaction.requiringNew().call(() ->
                AiUsageEntity.<AiUsageEntity>list(
                        "invocationType = 'scheduled-job' and actionType = ?1", name));
        assertEquals(1, usage.size());
        assertEquals(ctx.traceId(), usage.get(0).traceId);
        assertFalse(usage.get(0).createdOn.isBefore(start));
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

    // ── Event stream orchestrator ────────────────────────────────────

    @Test
    void managerIgnoreActivityCarriesRoutingTrace() {
        Mockito.when(managerService.meetsConfidenceThreshold(ArgumentMatchers.any()))
                .thenReturn(true);
        Mockito.when(managerService.evaluateStreamEvent(ArgumentMatchers.any(),
                        ArgumentMatchers.any()))
                .thenReturn(ManagerEvaluationResult.success(List.of(new ManagerDecision(
                        "ignore", null, null, null, 0.9, "not relevant", null, null)), null));

        UUID eventId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity event = new StreamEventEntity();
            event.id = eventId;
            event.sourceEventId = "corr-" + eventId;
            event.source = "github";
            event.connectionId = "conn-corr";
            event.type = "issue.created";
            event.ref = "https://github.com/test-org/test-repo/issues/" + eventId;
            event.timestamp = Instant.now();
            event.actor = "{\"login\":\"testuser\"}";
            event.payload = "{\"issue\":{\"title\":\"Test\",\"state\":\"open\",\"number\":\"1\"}}";
            event.createdOn = Instant.now();
            event.persist();

            EventSubscriptionEntity sub = new EventSubscriptionEntity();
            sub.name = "corr-manager";
            sub.enabled = true;
            sub.routing = "[{\"type\":\"manager\"}]";
            sub.createdOn = Instant.now();
            sub.modifiedOn = Instant.now();
            sub.persist();
        });

        orchestrator.processNewEvents();

        RoutingOutcomeEntity outcome = QuarkusTransaction.requiringNew().call(() ->
                RoutingOutcomeEntity.<RoutingOutcomeEntity>find("routingType", "manager")
                        .firstResult());
        assertNotNull(outcome);
        assertNotNull(outcome.traceId);
        ActivityLogEntity row = QuarkusTransaction.requiringNew().call(() ->
                ActivityLogEntity.<ActivityLogEntity>find(
                        "eventId = ?1 and entryType = 'event-ignored'", eventId).firstResult());
        assertNotNull(row);
        assertEquals(outcome.traceId, row.traceId);
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

    private Long[] createReport() {
        return QuarkusTransaction.requiringNew().call(() -> {
            ReportDefinitionEntity def = new ReportDefinitionEntity();
            def.name = "corr-report-" + UUID.randomUUID();
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
    }

    private ActivityLogEntity activityRow(String entryType, String nameFragment) {
        ActivityLogEntity row = QuarkusTransaction.requiringNew().call(() ->
                ActivityLogEntity.<ActivityLogEntity>find(
                        "entryType = ?1 and summary like ?2", entryType,
                        "%" + nameFragment + "%").firstResult());
        assertNotNull(row, "Missing activity row " + entryType + " for " + nameFragment);
        return row;
    }
}
