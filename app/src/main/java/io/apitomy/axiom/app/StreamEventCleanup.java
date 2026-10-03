package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ConnectionPollLogEntity;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Periodically deletes stream events and their processing ledger entries
 * that have exceeded the configured retention period ({@code eventRetentionDays}).
 * Events still needed by an active workflow run are kept (see {@link #cleanupBatch()}).
 */
@ApplicationScoped
public class StreamEventCleanup {

    private static final Logger LOG = Logger.getLogger(StreamEventCleanup.class);

    private volatile boolean shuttingDown = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    /** Maximum number of events deleted per transaction. */
    static final int BATCH_SIZE = 500;

    /**
     * Workflow run statuses for which the run is still in progress. Events that such a run was
     * started or resumed by are kept until the run finishes.
     */
    static final List<String> ACTIVE_RUN_STATUSES = List.of("running", "waiting");

    /**
     * Finds and deletes stream events older than the retention period, along with their
     * processing ledger entries and routing outcomes, one batch per transaction. Then deletes
     * old connection poll logs.
     */
    @Scheduled(every = "1h", delayed = "6m",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void cleanup() {
        int[] deleted = {BATCH_SIZE};
        while (!shuttingDown && deleted[0] >= BATCH_SIZE) {
            deleted[0] = 0;
            CleanupRetry.runWithRetry(LOG, "Stream event cleanup", () -> shuttingDown,
                    () -> deleted[0] = cleanupBatch());
        }
        CleanupRetry.runWithRetry(LOG, "Connection poll log cleanup", () -> shuttingDown,
                this::cleanupPollLogs);
    }

    /**
     * Runs the whole cleanup in the current transaction. Used by tests; the scheduler uses one
     * transaction per batch.
     */
    void doCleanup() {
        while (cleanupBatch() >= BATCH_SIZE) {
            // keep going until a partial batch is deleted
        }
        cleanupPollLogs();
    }

    /**
     * Deletes one batch of stale stream events. An event is kept, even when it is older than the
     * retention period, while a workflow run that is still active ({@link #ACTIVE_RUN_STATUSES})
     * references it as its trigger event or through a {@code workflow_run_resume} row. Such
     * events become eligible on the first cleanup after the run finishes.
     *
     * @return the number of events deleted
     */
    int cleanupBatch() {
        RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                .firstResult();
        if (config == null) {
            return 0;
        }

        Instant cutoff = Instant.now().minus(config.eventRetentionDays, ChronoUnit.DAYS);
        List<UUID> eventIds = StreamEventEntity.getEntityManager()
                .createQuery("select e.id from StreamEventEntity e where e.createdOn < :cutoff "
                        + "and e.id not in (select r.triggerEventId from WorkflowRunEntity r "
                        + "    where r.status in :active and r.triggerEventId is not null) "
                        + "and e.id not in (select rr.eventId from WorkflowRunResumeEntity rr, "
                        + "    WorkflowRunEntity r2 where rr.runId = r2.id and r2.status in :active)",
                        UUID.class)
                .setParameter("cutoff", cutoff)
                .setParameter("active", ACTIVE_RUN_STATUSES)
                .setMaxResults(BATCH_SIZE)
                .getResultList();

        if (eventIds.isEmpty()) {
            return 0;
        }

        // Delete routing outcome items and outcomes explicitly. The Flyway schema cascades
        // these from the ledger, but a schema generated from the entities has no cascade.
        RoutingOutcomeItemEntity.delete("outcomeId in (select o.id from RoutingOutcomeEntity o "
                + "where o.ledgerId in (select l.id from EventProcessingLedgerEntity l "
                + "where l.eventId in ?1))", eventIds);
        RoutingOutcomeEntity.delete("ledgerId in (select l.id from EventProcessingLedgerEntity l "
                + "where l.eventId in ?1)", eventIds);

        // Delete associated ledger entries first (FK constraint)
        long ledgerDeleted = EventProcessingLedgerEntity.delete("eventId in ?1", eventIds);
        long eventsDeleted = StreamEventEntity.delete("id in ?1", eventIds);

        LOG.infof("Cleaned up %d stream event(s) and %d ledger entries older than %d days",
                eventsDeleted, ledgerDeleted, config.eventRetentionDays);
        return eventIds.size();
    }

    /**
     * Deletes connection poll logs older than the fixed 3-day retention.
     */
    void cleanupPollLogs() {
        Instant pollLogCutoff = Instant.now().minus(3, ChronoUnit.DAYS);
        long pollLogsDeleted = ConnectionPollLogEntity.delete("createdOn < ?1", pollLogCutoff);
        if (pollLogsDeleted > 0) {
            LOG.infof("Cleaned up %d connection poll log(s) older than 3 days", pollLogsDeleted);
        }
    }
}
