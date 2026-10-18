package io.apitomy.axiom.core.events;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;

/**
 * Sends the {@code stream-event} SSE notification once a new stream event has been committed,
 * so clients never reload before the event is visible (and never for rolled-back inserts).
 */
@ApplicationScoped
public class StreamEventSseNotifier {

    @Inject
    Event<SseEvent> sseEvents;

    /**
     * Fires the {@code stream-event} SSE event after the persisting transaction committed.
     * Without an active transaction the observer runs immediately.
     *
     * @param persisted the persisted stream event
     */
    void onStreamEventPersisted(@Observes(during = TransactionPhase.AFTER_SUCCESS)
                                StreamEventPersisted persisted) {
        sseEvents.fire(SseEvent.streamEventReceived(persisted.eventId(), persisted.eventType(),
                persisted.eventSourceId()));
    }
}
