package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.axiom.core.events.StreamEventPersisted;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@code stream-event} and {@code stream-processing} SSE notifications used by
 * dashboard widgets for live refresh.
 */
@QuarkusTest
class StreamSseNotificationsTest {

    /**
     * Records every SSE event fired in the application.
     */
    @Singleton
    public static class SseRecorder {
        final List<SseEvent> events = new CopyOnWriteArrayList<>();

        void onSse(@Observes SseEvent event) {
            events.add(event);
        }

        List<SseEvent> ofType(String type) {
            return events.stream().filter(e -> type.equals(e.type())).toList();
        }
    }

    @Inject
    SseRecorder recorder;

    @Inject
    Event<StreamEventPersisted> persistedEvents;

    @Inject
    EventStreamOrchestrator orchestrator;

    @Inject
    SdkCallService sdkCallService;

    @BeforeEach
    void clear() {
        // Flush ledger changes recorded by other tests so only this test's changes count
        orchestrator.notifyLedgerChanges();
        recorder.events.clear();
    }

    @AfterEach
    @Transactional
    void cleanup() {
        EventProcessingLedgerEntity.deleteAll();
        StreamEventEntity.deleteAll();
        EventSubscriptionEntity.deleteAll();
    }

    @Test
    void streamEventIsSentOnlyAfterCommit() {
        QuarkusTransaction.requiringNew().run(() -> {
            persistedEvents.fire(new StreamEventPersisted("id-1", "issue.created", "src-1"));
            assertTrue(recorder.ofType("stream-event").isEmpty(), "must not be sent before commit");
        });
        List<SseEvent> sent = recorder.ofType("stream-event");
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).data().contains("\"eventId\":\"id-1\""));
        assertTrue(sent.get(0).data().contains("\"eventType\":\"issue.created\""));
        assertTrue(sent.get(0).data().contains("\"eventSourceId\":\"src-1\""));
    }

    @Test
    void streamEventIsNotSentOnRollback() {
        QuarkusTransaction.begin();
        persistedEvents.fire(new StreamEventPersisted("id-2", "issue.created", "src-1"));
        QuarkusTransaction.rollback();
        assertTrue(recorder.ofType("stream-event").isEmpty());
    }

    @Test
    void sdkFiredEventNotifiesClients() {
        QuarkusTransaction.requiringNew().run(() -> sdkCallService.execute("axiom_fire_event",
                java.util.Map.of("source", "sdk", "eventType", "custom.thing", "payload", "{}")));
        List<SseEvent> sent = recorder.ofType("stream-event");
        assertEquals(1, sent.size(), "sent: " + sent);
        assertTrue(sent.get(0).data().contains("\"eventType\":\"custom.thing\""));
    }

    @Test
    void ledgerChangesAreCoalescedIntoOneStreamProcessingEventPerTick() {
        for (int i = 0; i < 5; i++) {
            insertStreamEvent("sse-ev-" + i);
        }
        QuarkusTransaction.requiringNew().run(() -> {
            EventSubscriptionEntity sub = new EventSubscriptionEntity();
            sub.name = "sse-all";
            sub.enabled = true;
            sub.createdOn = Instant.now();
            sub.modifiedOn = Instant.now();
            sub.persist();
        });

        orchestrator.processNewEvents();

        List<SseEvent> sent = recorder.ofType("stream-processing");
        assertEquals(1, sent.size(), "one coalesced event per tick: " + recorder.events);
        assertTrue(sent.get(0).data().matches("\\{\"changes\":\\d+}"), sent.get(0).data());

        recorder.events.clear();
        orchestrator.processNewEvents();
        assertTrue(recorder.ofType("stream-processing").isEmpty(),
                "no event when nothing changed: " + recorder.events);
    }

    private void insertStreamEvent(String sourceEventId) {
        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity event = new StreamEventEntity();
            event.id = UUID.randomUUID();
            event.sourceEventId = sourceEventId;
            event.source = "github";
            event.eventSourceId = "conn-1";
            event.type = "issue.created";
            event.ref = "https://example.com/1";
            event.timestamp = Instant.now();
            event.actor = "{\"login\":\"u\"}";
            event.payload = "{}";
            event.createdOn = Instant.now();
            event.persist();
        });
    }
}
