package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.EventQueueEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Periodically deletes processed event queue entries older than one day.
 * This is not configurable — processed queue entries are internal bookkeeping
 * with no user-facing value.
 */
@ApplicationScoped
public class EventQueueCleanup {

    private static final Logger LOG = Logger.getLogger(EventQueueCleanup.class);

    private volatile boolean shuttingDown = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    /**
     * Deletes processed event queue entries older than one day.
     */
    @Scheduled(every = "1h", delayed = "1m",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void cleanup() {
        if (shuttingDown) {
            return;
        }
        CleanupRetry.runWithRetry(LOG, "Event queue cleanup", this::doCleanup);
    }

    void doCleanup() {
        Instant cutoff = Instant.now().minus(1, ChronoUnit.DAYS);
        long deleted = EventQueueEntity.delete(
                "processedAt is not null and processedAt < ?1", cutoff);
        if (deleted > 0) {
            LOG.infof("Cleaned up %d processed event queue entry/entries older than 1 day",
                    deleted);
        }
    }
}
