package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunResumeEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.ReportEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.apitomy.axiom.core.entities.ScheduledJobRunEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link TraceCleanup}.
 */
@QuarkusTest
class TraceCleanupTest {

    @Inject
    TraceCleanup traceCleanup;

    private Integer originalRetentionDays;

    @AfterEach
    void restoreRetention() {
        if (originalRetentionDays != null) {
            int restored = originalRetentionDays;
            QuarkusTransaction.requiringNew().run(() -> {
                RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                        .firstResult();
                config.traceRetentionDays = restored;
            });
            originalRetentionDays = null;
        }
    }

    @Test
    void cleanupClearsActivityAndAiUsageTraceReferences() {
        UUID traceId = UUID.randomUUID();
        String marker = "trace-cleanup-test-" + traceId;
        Long[] ids = new Long[2];

        QuarkusTransaction.requiringNew().run(() -> {
            RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                    .firstResult();
            if (config == null) {
                config = new RetentionConfigEntity();
                config.closedProjectRetentionDays = 30;
                config.eventRetentionDays = 30;
                config.traceRetentionDays = 30;
                config.persist();
            }
            originalRetentionDays = config.traceRetentionDays;
            config.traceRetentionDays = 1;

            TraceEntity trace = new TraceEntity();
            trace.traceId = traceId;
            trace.traceType = "test";
            trace.status = "completed";
            trace.summary = marker;
            trace.startedOn = Instant.now().minus(5, ChronoUnit.DAYS);
            trace.persist();

            ActivityLogEntity activity = new ActivityLogEntity();
            activity.traceId = traceId;
            activity.entryType = "test";
            activity.summary = marker;
            activity.createdOn = Instant.now();
            activity.persist();
            ids[0] = activity.id;

            AiUsageEntity usage = new AiUsageEntity();
            usage.traceId = traceId;
            usage.invocationType = marker;
            usage.createdOn = Instant.now();
            usage.persist();
            ids[1] = usage.id;
        });

        QuarkusTransaction.requiringNew().run(() -> traceCleanup.doCleanup());

        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(TraceEntity.find("traceId", traceId).firstResult());
            ActivityLogEntity activity = ActivityLogEntity.findById(ids[0]);
            AiUsageEntity usage = AiUsageEntity.findById(ids[1]);
            assertNotNull(activity);
            assertNotNull(usage);
            assertNull(activity.traceId);
            assertNull(usage.traceId);
            activity.delete();
            usage.delete();
        });
    }

    @Test
    void cleanupClearsTriggeringTraceReferences() {
        UUID traceId = UUID.randomUUID();
        Long[] ids = new Long[2];

        QuarkusTransaction.requiringNew().run(() -> {
            RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                    .firstResult();
            if (config == null) {
                config = new RetentionConfigEntity();
                config.closedProjectRetentionDays = 30;
                config.eventRetentionDays = 30;
                config.traceRetentionDays = 30;
                config.persist();
            }
            originalRetentionDays = config.traceRetentionDays;
            config.traceRetentionDays = 1;

            TraceEntity trace = new TraceEntity();
            trace.traceId = traceId;
            trace.traceType = "test";
            trace.status = "completed";
            trace.summary = "trigger-cleanup-" + traceId;
            trace.startedOn = Instant.now().minus(5, ChronoUnit.DAYS);
            trace.persist();

            ScheduledJobRunEntity run = new ScheduledJobRunEntity();
            run.jobId = -1L;
            run.status = "Completed";
            run.trigger = "manual";
            run.triggeredBy = "manual";
            run.triggeredByTraceId = traceId;
            run.createdOn = Instant.now();
            run.persist();
            ids[0] = run.id;

            ReportEntity report = new ReportEntity();
            report.definitionId = -1L;
            report.status = "Completed";
            report.trigger = "manual";
            report.triggeredBy = "manual";
            report.triggeredByTraceId = traceId;
            report.createdOn = Instant.now();
            report.persist();
            ids[1] = report.id;
        });

        QuarkusTransaction.requiringNew().run(() -> traceCleanup.doCleanup());

        QuarkusTransaction.requiringNew().run(() -> {
            ScheduledJobRunEntity run = ScheduledJobRunEntity.findById(ids[0]);
            ReportEntity report = ReportEntity.findById(ids[1]);
            assertNull(run.triggeredByTraceId);
            assertNull(report.triggeredByTraceId);
            run.delete();
            report.delete();
        });
    }

    /**
     * Every column (as {@code table.column}) that references a trace or a trace node, and how
     * {@link TraceCleanup} handles it. Adding a new trace column to an entity makes
     * {@link #everyTraceColumnIsHandled()} fail until the column is handled by the cleanup,
     * covered by a test here, and listed below.
     */
    private static final Set<String> HANDLED_TRACE_COLUMNS = Set.of(
            // The trace itself and its children: deleted with the trace.
            "trace.trace_id",
            "trace_node.trace_id",
            "tool_execution.trace_id",
            // Cleared (set to null) when the trace is deleted.
            "task.trace_id",
            "scheduled_job_run.trace_id",
            "scheduled_job_run.triggered_by_trace_id",
            "report.trace_id",
            "report.triggered_by_trace_id",
            "activity_log.trace_id",
            "ai_usage.trace_id",
            "workflow_run.trace_id",
            "routing_outcome.trace_id",
            "routing_outcome_item.trace_node_id",
            "workflow_run_resume.trace_node_id");

    private static final Pattern TRACE_COLUMN = Pattern.compile("(^|_)trace(_node)?_id$");

    @Inject
    EntityManager entityManager;

    @Test
    void everyTraceColumnIsHandled() {
        Set<String> found = new TreeSet<>();
        for (EntityType<?> type : entityManager.getMetamodel().getEntities()) {
            Class<?> javaType = type.getJavaType();
            Table table = javaType.getAnnotation(Table.class);
            String tableName = table != null ? table.name() : javaType.getSimpleName();
            for (Class<?> c = javaType; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    Column column = field.getAnnotation(Column.class);
                    if (column != null && TRACE_COLUMN.matcher(column.name()).find()) {
                        found.add(tableName + "." + column.name());
                    }
                }
            }
        }
        assertEquals(new TreeSet<>(HANDLED_TRACE_COLUMNS), found,
                "A trace column was added or removed; handle it in TraceCleanup and update this test");
    }

    @Test
    void cleanupClearsRunOutcomeAndTaskTraceReferences() {
        UUID traceId = UUID.randomUUID();
        Long[] ids = new Long[7];

        QuarkusTransaction.requiringNew().run(() -> {
            shortenTraceRetention();
            persistOldTrace(traceId);

            TraceNodeEntity node = new TraceNodeEntity();
            node.traceId = traceId;
            node.nodeType = "receive-event";
            node.status = "completed";
            node.summary = "node";
            node.startedOn = Instant.now().minus(5, ChronoUnit.DAYS);
            node.persist();

            WorkflowRunEntity run = new WorkflowRunEntity();
            run.projectId = -1L;
            run.definitionId = -1L;
            run.definitionVersion = 1;
            run.instanceState = "{}";
            run.status = "completed";
            run.traceId = traceId;
            run.startedOn = Instant.now();
            run.persist();
            ids[0] = run.id;

            RoutingOutcomeEntity outcome = new RoutingOutcomeEntity();
            outcome.ledgerId = -1L;
            outcome.routingType = "manager";
            outcome.status = "succeeded";
            outcome.traceId = traceId;
            outcome.createdOn = Instant.now();
            outcome.persist();
            ids[1] = outcome.id;

            RoutingOutcomeItemEntity item = new RoutingOutcomeItemEntity();
            item.outcomeId = outcome.id;
            item.itemType = RoutingOutcomeItemEntity.TYPE_WORKFLOW_RESUMED;
            item.status = "succeeded";
            item.traceNodeId = node.id;
            item.createdOn = Instant.now();
            item.persist();
            ids[2] = item.id;

            WorkflowRunResumeEntity resume = new WorkflowRunResumeEntity();
            resume.runId = run.id;
            resume.nodeId = "wait";
            resume.eventId = UUID.randomUUID();
            resume.traceNodeId = node.id;
            resume.resumedOn = Instant.now();
            resume.persist();
            ids[3] = resume.id;

            TaskEntity task = new TaskEntity();
            task.projectId = -1L;
            task.actionType = "test";
            task.createdBy = "test";
            task.status = "Completed";
            task.traceId = traceId;
            task.createdOn = Instant.now();
            task.persist();
            ids[4] = task.id;

            ScheduledJobRunEntity jobRun = new ScheduledJobRunEntity();
            jobRun.jobId = -1L;
            jobRun.status = "Completed";
            jobRun.trigger = "manual";
            jobRun.traceId = traceId;
            jobRun.createdOn = Instant.now();
            jobRun.persist();
            ids[5] = jobRun.id;

            ReportEntity report = new ReportEntity();
            report.definitionId = -1L;
            report.status = "Completed";
            report.traceId = traceId;
            report.createdOn = Instant.now();
            report.persist();
            ids[6] = report.id;
        });

        QuarkusTransaction.requiringNew().run(() -> traceCleanup.doCleanup());

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(0, TraceNodeEntity.count("traceId", traceId));
            WorkflowRunEntity run = WorkflowRunEntity.findById(ids[0]);
            RoutingOutcomeEntity outcome = RoutingOutcomeEntity.findById(ids[1]);
            RoutingOutcomeItemEntity item = RoutingOutcomeItemEntity.findById(ids[2]);
            WorkflowRunResumeEntity resume = WorkflowRunResumeEntity.findById(ids[3]);
            TaskEntity task = TaskEntity.findById(ids[4]);
            ScheduledJobRunEntity jobRun = ScheduledJobRunEntity.findById(ids[5]);
            ReportEntity report = ReportEntity.findById(ids[6]);
            assertNull(run.traceId);
            assertNull(outcome.traceId);
            assertNull(item.traceNodeId);
            assertNull(resume.traceNodeId);
            assertNull(task.traceId);
            assertNull(jobRun.traceId);
            assertNull(report.traceId);
            item.delete();
            outcome.delete();
            resume.delete();
            run.delete();
            task.delete();
            jobRun.delete();
            report.delete();
        });
    }

    private void shortenTraceRetention() {
        RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                .firstResult();
        originalRetentionDays = config.traceRetentionDays;
        config.traceRetentionDays = 1;
    }

    private static void persistOldTrace(UUID traceId) {
        TraceEntity trace = new TraceEntity();
        trace.traceId = traceId;
        trace.traceType = "test";
        trace.status = "completed";
        trace.summary = "trace-cleanup-" + traceId;
        trace.startedOn = Instant.now().minus(5, ChronoUnit.DAYS);
        trace.persist();
    }
}
