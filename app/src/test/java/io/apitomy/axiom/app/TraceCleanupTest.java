package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link TraceCleanup}.
 */
@QuarkusTest
class TraceCleanupTest {

    @Inject
    TraceCleanup traceCleanup;

    private Integer originalRetentionDays;

    @AfterEach
    void restoreRetention() {
        if (originalRetentionDays != null) {
            int restored = originalRetentionDays;
            QuarkusTransaction.requiringNew().run(() -> {
                RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                        .firstResult();
                config.traceRetentionDays = restored;
            });
            originalRetentionDays = null;
        }
    }

    @Test
    void cleanupClearsActivityAndAiUsageTraceReferences() {
        UUID traceId = UUID.randomUUID();
        String marker = "trace-cleanup-test-" + traceId;
        Long[] ids = new Long[2];

        QuarkusTransaction.requiringNew().run(() -> {
            RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                    .firstResult();
            if (config == null) {
                config = new RetentionConfigEntity();
                config.closedProjectRetentionDays = 30;
                config.eventRetentionDays = 30;
                config.traceRetentionDays = 30;
                config.persist();
            }
            originalRetentionDays = config.traceRetentionDays;
            config.traceRetentionDays = 1;

            TraceEntity trace = new TraceEntity();
            trace.traceId = traceId;
            trace.traceType = "test";
            trace.status = "completed";
            trace.summary = marker;
            trace.startedOn = Instant.now().minus(5, ChronoUnit.DAYS);
            trace.persist();

            ActivityLogEntity activity = new ActivityLogEntity();
            activity.traceId = traceId;
            activity.entryType = "test";
            activity.summary = marker;
            activity.createdOn = Instant.now();
            activity.persist();
            ids[0] = activity.id;

            AiUsageEntity usage = new AiUsageEntity();
            usage.traceId = traceId;
            usage.invocationType = marker;
            usage.createdOn = Instant.now();
            usage.persist();
            ids[1] = usage.id;
        });

        QuarkusTransaction.requiringNew().run(() -> traceCleanup.doCleanup());

        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(TraceEntity.find("traceId", traceId).firstResult());
            ActivityLogEntity activity = ActivityLogEntity.findById(ids[0]);
            AiUsageEntity usage = AiUsageEntity.findById(ids[1]);
            assertNotNull(activity);
            assertNotNull(usage);
            assertNull(activity.traceId);
            assertNull(usage.traceId);
            activity.delete();
            usage.delete();
        });
    }
}
