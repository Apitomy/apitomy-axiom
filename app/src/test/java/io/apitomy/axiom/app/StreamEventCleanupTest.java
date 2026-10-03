package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunResumeEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link StreamEventCleanup}: events that an active workflow run depends on survive.
 */
@QuarkusTest
class StreamEventCleanupTest {

    @Inject
    StreamEventCleanup streamEventCleanup;

    private Integer originalRetentionDays;

    @AfterEach
    void restoreRetention() {
        if (originalRetentionDays != null) {
            int restored = originalRetentionDays;
            QuarkusTransaction.requiringNew().run(() ->
                    RetentionConfigEntity.<RetentionConfigEntity>findAll().firstResult()
                            .eventRetentionDays = restored);
            originalRetentionDays = null;
        }
    }

    @Test
    void eventsReferencedByActiveRunsSurviveCleanup() {
        UUID triggerOfActive = UUID.randomUUID();
        UUID resumeOfActive = UUID.randomUUID();
        UUID triggerOfFinished = UUID.randomUUID();
        UUID unreferenced = UUID.randomUUID();
        long[] runIds = new long[2];

        QuarkusTransaction.requiringNew().run(() -> {
            RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                    .firstResult();
            originalRetentionDays = config.eventRetentionDays;
            config.eventRetentionDays = 1;

            for (UUID id : new UUID[] {triggerOfActive, resumeOfActive, triggerOfFinished,
                    unreferenced}) {
                persistOldEvent(id);
            }

            WorkflowRunEntity active = persistRun("waiting", triggerOfActive);
            runIds[0] = active.id;
            WorkflowRunResumeEntity resume = new WorkflowRunResumeEntity();
            resume.runId = active.id;
            resume.nodeId = "receive";
            resume.eventId = resumeOfActive;
            resume.resumedOn = Instant.now();
            resume.persist();

            runIds[1] = persistRun("completed", triggerOfFinished).id;
        });

        QuarkusTransaction.requiringNew().run(() -> streamEventCleanup.doCleanup());

        QuarkusTransaction.requiringNew().run(() -> {
            assertNotNull(StreamEventEntity.findById(triggerOfActive));
            assertEquals(1, EventProcessingLedgerEntity.count("eventId", triggerOfActive));
            assertNotNull(StreamEventEntity.findById(resumeOfActive));
            assertNull(StreamEventEntity.findById(triggerOfFinished));
            assertNull(StreamEventEntity.findById(unreferenced));
            assertEquals(0, EventProcessingLedgerEntity.count("eventId", unreferenced));

            WorkflowRunResumeEntity.delete("runId", runIds[0]);
            WorkflowRunEntity.deleteById(runIds[0]);
            WorkflowRunEntity.deleteById(runIds[1]);
            EventProcessingLedgerEntity.delete("eventId in ?1",
                    java.util.List.of(triggerOfActive, resumeOfActive));
            StreamEventEntity.deleteById(triggerOfActive);
            StreamEventEntity.deleteById(resumeOfActive);
        });
    }

    private static void persistOldEvent(UUID id) {
        Instant old = Instant.now().minus(5, ChronoUnit.DAYS);
        StreamEventEntity event = new StreamEventEntity();
        event.id = id;
        event.sourceEventId = "cleanup-test-" + id;
        event.source = "test";
        event.connectionId = "test";
        event.type = "test.event";
        event.ref = "ref";
        event.timestamp = old;
        event.actor = "{}";
        event.payload = "{}";
        event.createdOn = old;
        event.persist();

        EventProcessingLedgerEntity ledger = new EventProcessingLedgerEntity();
        ledger.eventId = id;
        ledger.subscriptionId = -1L;
        ledger.status = "processed";
        ledger.createdOn = old;
        ledger.persist();
    }

    private static WorkflowRunEntity persistRun(String status, UUID triggerEventId) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.projectId = -1L;
        run.definitionId = -1L;
        run.definitionVersion = 1;
        run.instanceState = "{}";
        run.status = status;
        run.triggerEventId = triggerEventId;
        run.startedOn = Instant.now();
        run.persist();
        return run;
    }
}
