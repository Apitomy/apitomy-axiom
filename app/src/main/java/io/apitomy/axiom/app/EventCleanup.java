package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.EventQueueEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Periodically deletes ingested events that have exceeded the configured
 * retention period. Cleans up related event queue entries and nullifies
 * eventId references on related entities.
 */
@ApplicationScoped
public class EventCleanup {

    private static final Logger LOG = Logger.getLogger(EventCleanup.class);

    private volatile boolean shuttingDown = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    /**
     * Finds and deletes events older than the retention period.
     */
    @Scheduled(every = "1h", delayed = "4m",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void cleanup() {
        if (shuttingDown) {
            return;
        }
        CleanupRetry.runWithRetry(LOG, "Event cleanup", this::doCleanup);
    }

    void doCleanup() {
        RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                .firstResult();
        if (config == null) {
            return;
        }

        Instant cutoff = Instant.now().minus(config.eventRetentionDays, ChronoUnit.DAYS);
        List<EventEntity> staleEvents = EventEntity
                .find("receivedAt < ?1", cutoff)
                .list();

        if (staleEvents.isEmpty()) {
            return;
        }

        List<Long> eventIds = staleEvents.stream().map(e -> e.id).toList();

        EventQueueEntity.delete("eventId in ?1", eventIds);
        ActivityLogEntity.update("eventId = null where eventId in ?1", eventIds);
        AiUsageEntity.update("eventId = null where eventId in ?1", eventIds);
        TraceEntity.update("eventId = null where eventId in ?1", eventIds);

        long deleted = EventEntity.delete("receivedAt < ?1", cutoff);

        LOG.infof("Cleaned up %d event(s) older than %d days",
                deleted, config.eventRetentionDays);
    }
}
