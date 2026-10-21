package io.apitomy.axiom.events.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.events.StreamEventPersisted;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Persists normalized events to the stream_event table with deduplication.
 * Events with a sourceEventId that already exists are silently skipped.
 * Fires a {@link StreamEventPersisted} event (sent to SSE clients after commit) for each new event.
 */
@ApplicationScoped
public class EventStreamService {

    private static final Logger LOG = Logger.getLogger(EventStreamService.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Event<StreamEventPersisted> persistedEvents;

    /**
     * Returns which of the given source event IDs are already stored, with a single indexed query. Pollers use
     * it to skip events they have already ingested before doing any work on them.
     *
     * @param sourceEventIds the source event IDs to look up
     * @return the subset of {@code sourceEventIds} already stored
     */
    @Transactional
    public Set<String> existingSourceEventIds(Collection<String> sourceEventIds) {
        if (sourceEventIds.isEmpty()) {
            return Set.of();
        }
        List<String> found = StreamEventEntity.getEntityManager()
                .createQuery("select e.sourceEventId from StreamEventEntity e where e.sourceEventId in :ids",
                        String.class)
                .setParameter("ids", sourceEventIds)
                .getResultList();
        return new HashSet<>(found);
    }

    /**
     * Persists a normalized event to the stream. If an event with the same
     * sourceEventId already exists, the event is silently skipped (dedup).
     *
     * <p>Payload serialization happens before the duplicate check; a serialization failure
     * raises {@link EventPersistenceException} so callers never mistake it for a duplicate.
     * A concurrent insert of the same {@code sourceEventId} between the check and the commit
     * is not mapped to a duplicate here: the constraint violation surfaces at commit time
     * (outside this method) and the transaction is already rollback-only, so it propagates as
     * an error and the poller retries the window, where the check then reports a duplicate.</p>
     *
     * @param event the normalized event to persist
     * @return true if the event was persisted, false if it was a duplicate
     * @throws EventPersistenceException if the event payload cannot be serialized
     */
    @Transactional
    public boolean persistEvent(NormalizedEvent event) {
        StreamEventEntity entity = new StreamEventEntity();
        entity.id = UUID.fromString(event.id());
        entity.sourceEventId = event.sourceEventId();
        entity.source = event.source();
        entity.eventSourceId = event.eventSourceId();
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
            throw new EventPersistenceException(
                    "Failed to serialize event payload for " + event.sourceEventId(), e);
        }

        // Dedup check
        long existing = StreamEventEntity.count("sourceEventId", event.sourceEventId());
        if (existing > 0) {
            LOG.debugf("Skipping duplicate event: %s (sourceEventId: %s)",
                    event.type().value(), event.sourceEventId());
            return false;
        }

        entity.createdOn = Instant.now();
        entity.persist();

        LOG.debugf("Persisted event %s: %s [%s]", entity.id, event.type().value(), event.ref());

        // Notify SSE clients (after commit) that a new event arrived in the stream
        persistedEvents.fire(new StreamEventPersisted(
                entity.id.toString(), event.type().value(), event.eventSourceId()));

        return true;
    }
}
