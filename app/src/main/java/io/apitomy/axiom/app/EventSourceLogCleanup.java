package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.EventSourceLogEntity;
import io.apitomy.axiom.core.services.SystemSettingsService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Periodically deletes old event source poll logs to prevent unbounded
 * table growth. Runs once per hour and removes entries older than the
 * configured retention period.
 */
@ApplicationScoped
public class EventSourceLogCleanup {

    private static final Logger LOG = Logger.getLogger(EventSourceLogCleanup.class);

    @Inject
    SystemSettingsService settingsService;

    /**
     * Deletes event source log entries older than the retention period.
     */
    @Scheduled(every = "1h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void cleanup() {
        int retentionDays = settingsService.getEventSourceLogRetentionDays();
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        long deleted = EventSourceLogEntity.delete("createdOn < ?1", cutoff);
        if (deleted > 0) {
            LOG.infof("Cleaned up %d event source log(s) older than %d days", deleted, retentionDays);
        }
    }
}
