package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.events.StreamEventPersisted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists internally generated stream events (SDK calls, system lifecycle events) so event subscriptions
 * and waiting workflows can react to them like any external event.
 */
@ApplicationScoped
public class SystemEventPublisher {

    @Inject
    Event<StreamEventPersisted> persistedEvents;

    /**
     * Persists a stream event and announces it. Must be called inside a transaction.
     *
     * @param eventSourceId the event source id (e.g. {@code "sdk"}, {@code "system"})
     * @param source        the source name (e.g. {@code "axiom"})
     * @param type          the event type (e.g. {@code "secret.expired"})
     * @param ref           the event reference, may be empty
     * @param actorJson     the actor JSON
     * @param payloadJson   the payload JSON
     * @param timestamp     when the event happened
     * @return the new event's id
     */
    public UUID publish(String eventSourceId, String source, String type, String ref, String actorJson,
                        String payloadJson, Instant timestamp) {
        StreamEventEntity event = new StreamEventEntity();
        event.id = UUID.randomUUID();
        event.sourceEventId = eventSourceId + "-" + event.id;
        event.source = source;
        event.eventSourceId = eventSourceId;
        event.type = type;
        event.ref = ref;
        event.timestamp = timestamp;
        event.actor = actorJson;
        event.payload = payloadJson;
        event.createdOn = Instant.now();
        event.persist();
        persistedEvents.fire(new StreamEventPersisted(event.id.toString(), event.type, event.eventSourceId));
        return event.id;
    }
}
