package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.ReportEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.ScheduledJobRunEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowEventSubscriptionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunResumeEntity;
import io.apitomy.axiom.core.entities.WorkflowWaitEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link HistoryCleanup}: the retention settings for scheduled job runs, reports,
 * workflow runs, activity log entries and AI usage records.
 */
@QuarkusTest
class HistoryCleanupTest {

    private static final Instant OLD = Instant.now().minus(10, ChronoUnit.DAYS);
    private static final Instant RECENT = Instant.now().minus(1, ChronoUnit.DAYS);

    @Inject
    HistoryCleanup historyCleanup;

    private int[] original;

    @BeforeEach
    void saveRetention() {
        QuarkusTransaction.requiringNew().run(() -> {
            RetentionConfigEntity c = config();
            original = new int[] {c.scheduledJobRunRetentionDays, c.reportRetentionDays,
                    c.workflowRunRetentionDays, c.activityLogRetentionDays,
                    c.aiUsageRetentionDays};
        });
    }

    @AfterEach
    void restoreRetention() {
        setRetention(original[0], original[1], original[2], original[3], original[4]);
    }

    @Test
    void newSettingsDefaultToKeepForever() {
        QuarkusTransaction.requiringNew().run(() -> {
            RetentionConfigEntity c = config();
            assertEquals(0, c.scheduledJobRunRetentionDays);
            assertEquals(0, c.reportRetentionDays);
            assertEquals(0, c.workflowRunRetentionDays);
            assertEquals(0, c.activityLogRetentionDays);
            assertEquals(0, c.aiUsageRetentionDays);
        });
    }

    @Test
    void zeroSettingsKeepEverything() {
        setRetention(0, 0, 0, 0, 0);
        long[] ids = QuarkusTransaction.requiringNew().call(() -> new long[] {
            jobRun("Completed", OLD).id, report("Completed", OLD).id,
            workflowRun("completed", OLD).id, activity(OLD).id, usage(OLD).id});

        QuarkusTransaction.requiringNew().run(() -> historyCleanup.doCleanup());

        QuarkusTransaction.requiringNew().run(() -> {
            assertNotNull(ScheduledJobRunEntity.findById(ids[0]));
            assertNotNull(ReportEntity.findById(ids[1]));
            assertNotNull(WorkflowRunEntity.findById(ids[2]));
            assertNotNull(ActivityLogEntity.findById(ids[3]));
            assertNotNull(AiUsageEntity.findById(ids[4]));
            ScheduledJobRunEntity.deleteById(ids[0]);
            ReportEntity.<ReportEntity>findById(ids[1]).delete();
            WorkflowRunEntity.deleteById(ids[2]);
            ActivityLogEntity.deleteById(ids[3]);
            AiUsageEntity.deleteById(ids[4]);
        });
    }

    @Test
    void scheduledJobRunRetentionDeletesOldFinishedRuns() {
        setRetention(5, 0, 0, 0, 0);
        long[] ids = QuarkusTransaction.requiringNew().call(() -> new long[] {
            jobRun("Completed", OLD).id, jobRun("Failed", OLD).id,
            jobRun("Running", OLD).id, jobRun("Completed", RECENT).id});

        QuarkusTransaction.requiringNew().run(() -> historyCleanup.doCleanup());

        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(ScheduledJobRunEntity.findById(ids[0]));
            assertNull(ScheduledJobRunEntity.findById(ids[1]));
            assertNotNull(ScheduledJobRunEntity.findById(ids[2]), "unfinished runs are kept");
            assertNotNull(ScheduledJobRunEntity.findById(ids[3]));
            ScheduledJobRunEntity.deleteById(ids[2]);
            ScheduledJobRunEntity.deleteById(ids[3]);
        });
    }

    @Test
    void reportRetentionDeletesOldFinishedReports() {
        setRetention(0, 5, 0, 0, 0);
        long[] ids = QuarkusTransaction.requiringNew().call(() -> new long[] {
            report("Completed", OLD).id, report("Generating", OLD).id,
            report("Completed", RECENT).id});

        QuarkusTransaction.requiringNew().run(() -> historyCleanup.doCleanup());

        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(ReportEntity.findById(ids[0]));
            assertNotNull(ReportEntity.findById(ids[1]), "unfinished reports are kept");
            assertNotNull(ReportEntity.findById(ids[2]));
            ReportEntity.<ReportEntity>findById(ids[1]).delete();
            ReportEntity.<ReportEntity>findById(ids[2]).delete();
        });
    }

    @Test
    void workflowRunRetentionDeletesOldFinishedRunsAndDependents() {
        setRetention(0, 0, 5, 0, 0);
        long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            WorkflowRunEntity old = workflowRun("completed", OLD);
            WorkflowWaitEntity wait = new WorkflowWaitEntity();
            wait.runId = old.id;
            wait.nodeId = "wait";
            wait.resumeAt = OLD;
            wait.createdOn = OLD;
            wait.persist();
            WorkflowEventSubscriptionEntity sub = new WorkflowEventSubscriptionEntity();
            sub.runId = old.id;
            sub.nodeId = "receive";
            sub.eventType = "pr.opened";
            sub.projectId = -1L;
            sub.createdOn = OLD;
            sub.persist();
            WorkflowRunResumeEntity resume = new WorkflowRunResumeEntity();
            resume.runId = old.id;
            resume.nodeId = "receive";
            resume.eventId = UUID.randomUUID();
            resume.resumedOn = OLD;
            resume.persist();
            TaskEntity task = new TaskEntity();
            task.projectId = -1L;
            task.actionType = "test";
            task.createdBy = "test";
            task.status = "Completed";
            task.workflowRunId = old.id;
            task.createdOn = OLD;
            task.persist();
            RoutingOutcomeItemEntity item = new RoutingOutcomeItemEntity();
            item.outcomeId = -1L;
            item.itemType = RoutingOutcomeItemEntity.TYPE_WORKFLOW_RUN;
            item.status = "succeeded";
            item.workflowRunId = old.id;
            item.createdOn = OLD;
            item.persist();
            return new long[] {old.id, workflowRun("waiting", OLD).id,
                workflowRun("completed", RECENT).id, task.id, item.id};
        });

        QuarkusTransaction.requiringNew().run(() -> historyCleanup.doCleanup());

        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(WorkflowRunEntity.findById(ids[0]));
            assertEquals(0, WorkflowWaitEntity.count("runId", ids[0]));
            assertEquals(0, WorkflowEventSubscriptionEntity.count("runId", ids[0]));
            assertEquals(0, WorkflowRunResumeEntity.count("runId", ids[0]));
            TaskEntity task = TaskEntity.findById(ids[3]);
            assertNotNull(task, "tasks are kept with the project");
            assertNull(task.workflowRunId);
            RoutingOutcomeItemEntity item = RoutingOutcomeItemEntity.findById(ids[4]);
            assertNull(item.workflowRunId);
            assertNotNull(WorkflowRunEntity.findById(ids[1]), "active runs are kept");
            assertNotNull(WorkflowRunEntity.findById(ids[2]));
            task.delete();
            item.delete();
            WorkflowRunEntity.deleteById(ids[1]);
            WorkflowRunEntity.deleteById(ids[2]);
        });
    }

    @Test
    void activityAndAiUsageRetentionDeleteOldRows() {
        setRetention(0, 0, 0, 5, 5);
        long[] ids = QuarkusTransaction.requiringNew().call(() -> new long[] {
            activity(OLD).id, activity(RECENT).id, usage(OLD).id, usage(RECENT).id});

        QuarkusTransaction.requiringNew().run(() -> historyCleanup.doCleanup());

        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(ActivityLogEntity.findById(ids[0]));
            assertNotNull(ActivityLogEntity.findById(ids[1]));
            assertNull(AiUsageEntity.findById(ids[2]));
            assertNotNull(AiUsageEntity.findById(ids[3]));
            ActivityLogEntity.deleteById(ids[1]);
            AiUsageEntity.deleteById(ids[3]);
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static RetentionConfigEntity config() {
        return RetentionConfigEntity.<RetentionConfigEntity>findAll().firstResult();
    }

    private static void setRetention(int jobRuns, int reports, int workflowRuns, int activity,
            int aiUsage) {
        QuarkusTransaction.requiringNew().run(() -> {
            RetentionConfigEntity c = config();
            c.scheduledJobRunRetentionDays = jobRuns;
            c.reportRetentionDays = reports;
            c.workflowRunRetentionDays = workflowRuns;
            c.activityLogRetentionDays = activity;
            c.aiUsageRetentionDays = aiUsage;
        });
    }

    private static ScheduledJobRunEntity jobRun(String status, Instant createdOn) {
        ScheduledJobRunEntity run = new ScheduledJobRunEntity();
        run.jobId = -1L;
        run.status = status;
        run.trigger = "manual";
        run.createdOn = createdOn;
        run.persist();
        return run;
    }

    private static ReportEntity report(String status, Instant createdOn) {
        ReportEntity report = new ReportEntity();
        report.definitionId = -1L;
        report.status = status;
        report.createdOn = createdOn;
        report.labels.addAll(List.of("history-cleanup-test"));
        report.persist();
        return report;
    }

    private static WorkflowRunEntity workflowRun(String status, Instant when) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.projectId = -1L;
        run.definitionId = -1L;
        run.definitionVersion = 1;
        run.instanceState = "{}";
        run.status = status;
        run.startedOn = when;
        run.completedOn = "completed".equals(status) ? when : null;
        run.persist();
        return run;
    }

    private static ActivityLogEntity activity(Instant createdOn) {
        ActivityLogEntity entry = new ActivityLogEntity();
        entry.entryType = "test";
        entry.summary = "history-cleanup-test";
        entry.createdOn = createdOn;
        entry.persist();
        return entry;
    }

    private static AiUsageEntity usage(Instant createdOn) {
        AiUsageEntity usage = new AiUsageEntity();
        usage.invocationType = "history-cleanup-test";
        usage.createdOn = createdOn;
        usage.persist();
        return usage;
    }
}
