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
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Periodically deletes history rows that have exceeded their configured retention period:
 * finished scheduled job runs, finished reports, finished workflow runs, activity log entries
 * and AI usage records. A retention of {@code 0} days (the default) keeps the rows forever.
 *
 * <p>References to deleted rows are handled as follows:
 * <ul>
 *   <li>Workflow runs: their waits, event subscriptions and resume records are deleted;
 *       {@code task.workflow_run_id} and {@code routing_outcome_item.workflow_run_id} are cleared
 *       (tasks belong to the project and are deleted with it).</li>
 *   <li>Job runs and reports: {@code ai_usage}, {@code activity_log} and {@code trace} keep their
 *       {@code scheduled_job_run_id} / {@code report_id} values, which may then dangle, as
 *       documented in {@code docs/developer-guide/correlation.md}. Config versions are not
 *       affected.</li>
 * </ul>
 *
 * <p>Each step deletes at most {@link #BATCH_SIZE} rows per transaction.
 */
@ApplicationScoped
public class HistoryCleanup {

    private static final Logger LOG = Logger.getLogger(HistoryCleanup.class);

    /** Maximum number of rows deleted per transaction. */
    static final int BATCH_SIZE = 500;

    /** Statuses of scheduled job runs and reports that have finished. */
    static final List<String> FINISHED_STATUSES = List.of("Completed", "Failed");

    /**
     * Statuses of workflow runs that have finished (lower-case names of the flow engine's
     * {@code InstanceStatus}). Runs in any other status, including statuses added later, are kept.
     */
    static final List<String> FINISHED_WORKFLOW_RUN_STATUSES =
            List.of("completed", "failed", "cancelled");

    private volatile boolean shuttingDown = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    /**
     * Runs every cleanup step, one batch per transaction.
     */
    @Scheduled(every = "1h", delayed = "9m",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void cleanup() {
        runStep("Scheduled job run cleanup", this::deleteJobRunBatch);
        runStep("Report cleanup", this::deleteReportBatch);
        runStep("Workflow run cleanup", this::deleteWorkflowRunBatch);
        runStep("Activity log cleanup", this::deleteActivityBatch);
        runStep("AI usage cleanup", this::deleteAiUsageBatch);
    }

    /**
     * Runs every cleanup step in the current transaction. Used by tests; the scheduler uses one
     * transaction per batch.
     */
    void doCleanup() {
        for (IntSupplier step : List.<IntSupplier>of(this::deleteJobRunBatch,
                this::deleteReportBatch, this::deleteWorkflowRunBatch, this::deleteActivityBatch,
                this::deleteAiUsageBatch)) {
            while (step.getAsInt() >= BATCH_SIZE) {
                // keep going until a partial batch is deleted
            }
        }
    }

    private void runStep(String name, IntSupplier batch) {
        int[] deleted = {BATCH_SIZE};
        while (!shuttingDown && deleted[0] >= BATCH_SIZE) {
            deleted[0] = 0;
            CleanupRetry.runWithRetry(LOG, name, () -> shuttingDown,
                    () -> deleted[0] = batch.getAsInt());
        }
    }

    /**
     * Deletes one batch of finished scheduled job runs older than the retention period.
     *
     * @return the number of runs deleted
     */
    int deleteJobRunBatch() {
        Instant cutoff = cutoff(config().scheduledJobRunRetentionDays);
        if (cutoff == null) {
            return 0;
        }
        List<Long> ids = finishedIds("select r.id from ScheduledJobRunEntity r "
                + "where r.status in :finished and r.createdOn < :cutoff", cutoff);
        if (ids.isEmpty()) {
            return 0;
        }
        ScheduledJobRunEntity.delete("id in ?1", ids);
        LOG.infof("Cleaned up %d scheduled job run(s)", ids.size());
        return ids.size();
    }

    /**
     * Deletes one batch of finished reports (and their labels) older than the retention period.
     *
     * @return the number of reports deleted
     */
    int deleteReportBatch() {
        Instant cutoff = cutoff(config().reportRetentionDays);
        if (cutoff == null) {
            return 0;
        }
        List<Long> ids = finishedIds("select r.id from ReportEntity r "
                + "where r.status in :finished and r.createdOn < :cutoff", cutoff);
        if (ids.isEmpty()) {
            return 0;
        }
        em().createNativeQuery("delete from report_label where report_id in (:ids)")
                .setParameter("ids", ids)
                .executeUpdate();
        ReportEntity.delete("id in ?1", ids);
        LOG.infof("Cleaned up %d report(s)", ids.size());
        return ids.size();
    }

    /**
     * Deletes one batch of finished workflow runs, with their waits, event subscriptions and
     * resume records, whose completion (or start, if no completion time was recorded) is older
     * than the retention period. Only runs in {@link #FINISHED_WORKFLOW_RUN_STATUSES} are
     * deleted.
     *
     * @return the number of runs deleted
     */
    int deleteWorkflowRunBatch() {
        Instant cutoff = cutoff(config().workflowRunRetentionDays);
        if (cutoff == null) {
            return 0;
        }
        List<Long> ids = em().createQuery("select r.id from WorkflowRunEntity r "
                        + "where r.status in :finished "
                        + "and coalesce(r.completedOn, r.startedOn) < :cutoff", Long.class)
                .setParameter("finished", FINISHED_WORKFLOW_RUN_STATUSES)
                .setParameter("cutoff", cutoff)
                .setMaxResults(BATCH_SIZE)
                .getResultList();
        if (ids.isEmpty()) {
            return 0;
        }
        WorkflowWaitEntity.delete("runId in ?1", ids);
        WorkflowEventSubscriptionEntity.delete("runId in ?1", ids);
        WorkflowRunResumeEntity.delete("runId in ?1", ids);
        TaskEntity.update("workflowRunId = null where workflowRunId in ?1", ids);
        RoutingOutcomeItemEntity.update("workflowRunId = null where workflowRunId in ?1", ids);
        WorkflowRunEntity.delete("id in ?1", ids);
        LOG.infof("Cleaned up %d workflow run(s)", ids.size());
        return ids.size();
    }

    /**
     * Deletes one batch of activity log entries older than the retention period.
     *
     * @return the number of entries deleted
     */
    int deleteActivityBatch() {
        Instant cutoff = cutoff(config().activityLogRetentionDays);
        if (cutoff == null) {
            return 0;
        }
        List<Long> ids = ids("select a.id from ActivityLogEntity a where a.createdOn < :cutoff",
                cutoff);
        if (ids.isEmpty()) {
            return 0;
        }
        ActivityLogEntity.delete("id in ?1", ids);
        LOG.infof("Cleaned up %d activity log entr(ies)", ids.size());
        return ids.size();
    }

    /**
     * Deletes one batch of AI usage records older than the retention period.
     *
     * @return the number of records deleted
     */
    int deleteAiUsageBatch() {
        Instant cutoff = cutoff(config().aiUsageRetentionDays);
        if (cutoff == null) {
            return 0;
        }
        List<Long> ids = ids("select u.id from AiUsageEntity u where u.createdOn < :cutoff",
                cutoff);
        if (ids.isEmpty()) {
            return 0;
        }
        AiUsageEntity.delete("id in ?1", ids);
        LOG.infof("Cleaned up %d AI usage record(s)", ids.size());
        return ids.size();
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static RetentionConfigEntity config() {
        RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                .firstResult();
        return config != null ? config : new RetentionConfigEntity();
    }

    /**
     * Returns the cutoff for a retention period, or {@code null} if the period is {@code 0}
     * (keep forever) or negative.
     */
    private static Instant cutoff(int retentionDays) {
        return retentionDays > 0 ? Instant.now().minus(retentionDays, ChronoUnit.DAYS) : null;
    }

    private static List<Long> ids(String jpql, Instant cutoff) {
        return query(jpql, cutoff).getResultList();
    }

    private static List<Long> finishedIds(String jpql, Instant cutoff) {
        return query(jpql, cutoff).setParameter("finished", FINISHED_STATUSES).getResultList();
    }

    private static TypedQuery<Long> query(String jpql, Instant cutoff) {
        return em().createQuery(jpql, Long.class)
                .setParameter("cutoff", cutoff)
                .setMaxResults(BATCH_SIZE);
    }

    private static EntityManager em() {
        return RetentionConfigEntity.getEntityManager();
    }
}
