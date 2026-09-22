package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
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
 * that have exceeded the configured retention period. Uses the same
 * {@code eventRetentionDays} setting as the legacy event cleanup.
 */
@ApplicationScoped
public class StreamEventCleanup {

    private static final Logger LOG = Logger.getLogger(StreamEventCleanup.class);

    private volatile boolean shuttingDown = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    /**
     * Finds and deletes stream events older than the retention period,
     * along with their processing ledger entries.
     */
    @Scheduled(every = "1h", delayed = "6m",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void cleanup() {
        if (shuttingDown) {
            return;
        }
        CleanupRetry.runWithRetry(LOG, "Stream event cleanup",
                () -> shuttingDown, this::doCleanup);
    }

    void doCleanup() {
        RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                .firstResult();
        if (config == null) {
            return;
        }

        Instant cutoff = Instant.now().minus(config.eventRetentionDays, ChronoUnit.DAYS);

        // Find stale stream events
        List<StreamEventEntity> staleEvents = StreamEventEntity
                .find("createdOn < ?1", cutoff)
                .list();

        if (staleEvents.isEmpty()) {
            return;
        }

        List<UUID> eventIds = staleEvents.stream().map(e -> e.id).toList();

        // Delete associated ledger entries first (FK constraint)
        long ledgerDeleted = EventProcessingLedgerEntity
                .delete("eventId in ?1", eventIds);

        // Delete the stream events
        long eventsDeleted = StreamEventEntity
                .delete("createdOn < ?1", cutoff);

        LOG.infof("Cleaned up %d stream event(s) and %d ledger entries older than %d days",
                eventsDeleted, ledgerDeleted, config.eventRetentionDays);
    }
}
