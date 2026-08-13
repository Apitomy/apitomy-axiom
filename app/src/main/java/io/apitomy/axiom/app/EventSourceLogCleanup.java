package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.EventSourceLogEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
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

    /**
     * Deletes event source log entries older than the retention period.
     */
    @Scheduled(every = "1h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void cleanup() {
        RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                .firstResult();
        if (config == null) {
            return;
        }

        Instant cutoff = Instant.now().minus(config.eventSourceLogRetentionDays, ChronoUnit.DAYS);
        long deleted = EventSourceLogEntity.delete("createdOn < ?1", cutoff);
        if (deleted > 0) {
            LOG.infof("Cleaned up %d event source log(s) older than %d days",
                    deleted, config.eventSourceLogRetentionDays);
        }
    }
}
