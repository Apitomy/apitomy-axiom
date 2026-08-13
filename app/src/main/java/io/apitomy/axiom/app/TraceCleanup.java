package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.ReportEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.apitomy.axiom.core.entities.ScheduledJobRunEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ToolExecutionEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Periodically deletes execution traces (and their nodes and tool executions)
 * that have exceeded the configured retention period. Nullifies traceId
 * references on related entities to avoid dangling foreign keys.
 */
@ApplicationScoped
public class TraceCleanup {

    private static final Logger LOG = Logger.getLogger(TraceCleanup.class);

    /**
     * Finds and deletes traces older than the retention period.
     */
    @Scheduled(every = "1h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void cleanup() {
        RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                .firstResult();
        if (config == null) {
            return;
        }

        Instant cutoff = Instant.now().minus(config.traceRetentionDays, ChronoUnit.DAYS);
        List<TraceEntity> staleTraces = TraceEntity
                .find("startedOn < ?1", cutoff)
                .list();

        if (staleTraces.isEmpty()) {
            return;
        }

        List<UUID> traceIds = staleTraces.stream().map(t -> t.traceId).toList();

        ToolExecutionEntity.delete("traceId in ?1", traceIds);
        TraceNodeEntity.delete("traceId in ?1", traceIds);

        TaskEntity.update("traceId = null where traceId in ?1", traceIds);
        EventEntity.update("traceId = null where traceId in ?1", traceIds);
        ScheduledJobRunEntity.update("traceId = null where traceId in ?1", traceIds);
        ReportEntity.update("traceId = null where traceId in ?1", traceIds);

        TraceEntity.delete("startedOn < ?1", cutoff);

        LOG.infof("Cleaned up %d trace(s) older than %d days",
                staleTraces.size(), config.traceRetentionDays);
    }
}
