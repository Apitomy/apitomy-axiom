package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.ReportEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.apitomy.axiom.core.entities.ScheduledJobRunEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ToolExecutionEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunResumeEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Periodically deletes execution traces (and their nodes and tool executions)
 * that have exceeded the configured retention period. Clears every reference to a
 * deleted trace or trace node (see {@code docs/developer-guide/correlation.md}) so that no
 * row points at a deleted trace. Work is done in batches of {@link #BATCH_SIZE} traces.
 */
@ApplicationScoped
public class TraceCleanup {

    private static final Logger LOG = Logger.getLogger(TraceCleanup.class);

    private volatile boolean shuttingDown = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    /** Maximum number of traces deleted per transaction. */
    static final int BATCH_SIZE = 500;

    /**
     * Finds and deletes traces older than the retention period, one batch per transaction.
     */
    @Scheduled(every = "1h", delayed = "3m",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void cleanup() {
        int[] deleted = {BATCH_SIZE};
        while (!shuttingDown && deleted[0] >= BATCH_SIZE) {
            deleted[0] = 0;
            CleanupRetry.runWithRetry(LOG, "Trace cleanup", () -> shuttingDown,
                    () -> deleted[0] = cleanupBatch());
        }
    }

    /**
     * Deletes all traces older than the retention period in the current transaction, batch by
     * batch. Used by tests; the scheduler uses one transaction per batch.
     */
    void doCleanup() {
        while (cleanupBatch() >= BATCH_SIZE) {
            // keep going until a partial batch is deleted
        }
    }

    /**
     * Deletes one batch of stale traces and clears every reference to them.
     *
     * @return the number of traces deleted
     */
    int cleanupBatch() {
        RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                .firstResult();
        if (config == null) {
            return 0;
        }

        Instant cutoff = Instant.now().minus(config.traceRetentionDays, ChronoUnit.DAYS);
        List<UUID> traceIds = TraceEntity.getEntityManager()
                .createQuery("select t.traceId from TraceEntity t where t.startedOn < :cutoff",
                        UUID.class)
                .setParameter("cutoff", cutoff)
                .setMaxResults(BATCH_SIZE)
                .getResultList();

        if (traceIds.isEmpty()) {
            return 0;
        }

        // References to the batch's trace nodes, cleared before the nodes are deleted.
        String nodesOfBatch = "in (select n.id from TraceNodeEntity n where n.traceId in ?1)";
        RoutingOutcomeItemEntity.update("traceNodeId = null where traceNodeId " + nodesOfBatch,
                traceIds);
        WorkflowRunResumeEntity.update("traceNodeId = null where traceNodeId " + nodesOfBatch,
                traceIds);

        ToolExecutionEntity.delete("traceId in ?1", traceIds);
        TraceNodeEntity.delete("traceId in ?1", traceIds);

        TaskEntity.update("traceId = null where traceId in ?1", traceIds);
        ScheduledJobRunEntity.update("traceId = null where traceId in ?1", traceIds);
        ReportEntity.update("traceId = null where traceId in ?1", traceIds);
        ScheduledJobRunEntity.update(
                "triggeredByTraceId = null where triggeredByTraceId in ?1", traceIds);
        ReportEntity.update("triggeredByTraceId = null where triggeredByTraceId in ?1", traceIds);
        ActivityLogEntity.update("traceId = null where traceId in ?1", traceIds);
        AiUsageEntity.update("traceId = null where traceId in ?1", traceIds);
        WorkflowRunEntity.update("traceId = null where traceId in ?1", traceIds);
        RoutingOutcomeEntity.update("traceId = null where traceId in ?1", traceIds);

        long deleted = TraceEntity.delete("traceId in ?1", traceIds);

        LOG.infof("Cleaned up %d trace(s) older than %d days",
                deleted, config.traceRetentionDays);
        return traceIds.size();
    }
}
