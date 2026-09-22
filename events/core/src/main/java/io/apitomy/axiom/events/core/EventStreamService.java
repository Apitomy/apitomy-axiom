package io.apitomy.axiom.events.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists normalized events to the stream_event table with deduplication.
 * Events with a sourceEventId that already exists are silently skipped.
 * Fires an SSE event when a new event is successfully persisted.
 */
@ApplicationScoped
public class EventStreamService {

    private static final Logger LOG = Logger.getLogger(EventStreamService.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Event<SseEvent> sseEvents;

    /**
     * Persists a normalized event to the stream. If an event with the same
     * sourceEventId already exists, the event is silently skipped (dedup).
     *
     * @param event the normalized event to persist
     * @return true if the event was persisted, false if it was a duplicate
     */
    @Transactional
    public boolean persistEvent(NormalizedEvent event) {
        // Dedup check
        long existing = StreamEventEntity.count("sourceEventId", event.sourceEventId());
        if (existing > 0) {
            LOG.debugf("Skipping duplicate event: %s (sourceEventId: %s)",
                    event.type().value(), event.sourceEventId());
            return false;
        }

        StreamEventEntity entity = new StreamEventEntity();
        entity.id = UUID.fromString(event.id());
        entity.sourceEventId = event.sourceEventId();
        entity.source = event.source();
        entity.connectionId = event.connectionId();
        entity.type = event.type().value();
        entity.ref = event.ref();
        entity.timestamp = Instant.parse(event.timestamp());

        try {
            entity.actor = objectMapper.writeValueAsString(event.actor());
            entity.payload = objectMapper.writeValueAsString(event.payload());
            if (event.sourceData() != null) {
                entity.sourceData = objectMapper.writeValueAsString(event.sourceData());
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to serialize event payload for %s", event.sourceEventId());
            return false;
        }

        entity.createdOn = Instant.now();
        entity.persist();

        LOG.debugf("Persisted event %s: %s [%s]", entity.id, event.type().value(), event.ref());

        // Notify SSE clients that a new event arrived in the stream
        sseEvents.fire(SseEvent.streamEventReceived(
                entity.id.toString(), event.type().value(), event.connectionId()));

        return true;
    }
}
