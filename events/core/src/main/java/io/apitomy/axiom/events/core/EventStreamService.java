package io.apitomy.axiom.events.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.events.StreamEventPersisted;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.schema.EventSchemaRegistry;
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
 * Duplicates are detected per event source: an event whose sourceEventId the same event source already
 * stored is skipped. Every new event is validated against its schema; invalid events are still stored,
 * with their validation errors.
 * Fires a {@link StreamEventPersisted} event (sent to SSE clients after commit) for each new event.
 */
@ApplicationScoped
public class EventStreamService {

    private static final Logger LOG = Logger.getLogger(EventStreamService.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Event<StreamEventPersisted> persistedEvents;

    @Inject
    EventSchemaRegistry schemaRegistry;

    /**
     * Returns which of the given source event IDs the event source has already stored.
     *
     * @param eventSourceId  the event source
     * @param sourceEventIds the source event IDs to look up
     * @return the subset of {@code sourceEventIds} already stored for that event source
     */
    @Transactional
    public Set<String> existingSourceEventIds(String eventSourceId, Collection<String> sourceEventIds) {
        if (sourceEventIds.isEmpty()) {
            return Set.of();
        }
        List<String> found = StreamEventEntity.getEntityManager()
                .createQuery("select e.sourceEventId from StreamEventEntity e "
                        + "where e.eventSourceId = :source and e.sourceEventId in :ids", String.class)
                .setParameter("source", eventSourceId)
                .setParameter("ids", sourceEventIds)
                .getResultList();
        return new HashSet<>(found);
    }

    /**
     * Persists a normalized event to the stream. If the same event source already stored an event with
     * the same sourceEventId, the event is skipped (dedup is per event source). Events that fail schema
     * validation are still saved, with their validation errors.
     *
     * <p>Payload serialization happens before the duplicate check; a serialization failure
     * raises {@link EventPersistenceException} so callers never mistake it for a duplicate.
     * A concurrent insert of the same {@code sourceEventId} between the check and the commit
     * is not mapped to a duplicate here: the constraint violation surfaces at commit time
     * (outside this method) and the transaction is already rollback-only, so it propagates as
     * an error and the poller retries the window, where the check then reports a duplicate.</p>
     *
     * @param event the normalized event to persist
     * @return the outcome: persisted (valid or invalid) or duplicate
     * @throws EventPersistenceException if the event payload cannot be serialized
     */
    @Transactional
    public PersistOutcome persistEvent(NormalizedEvent event) {
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

        long existing = StreamEventEntity.count("eventSourceId = ?1 and sourceEventId = ?2",
                event.eventSourceId(), event.sourceEventId());
        if (existing > 0) {
            LOG.debugf("Skipping duplicate event: %s (sourceEventId: %s)",
                    event.type().value(), event.sourceEventId());
            return PersistOutcome.DUPLICATE;
        }

        List<String> errors = validationErrors(event);
        if (!errors.isEmpty()) {
            entity.validationErrors = toJson(errors);
            LOG.warnf("Event %s (%s) failed schema validation: %s",
                    event.sourceEventId(), event.type().value(), errors);
        }

        entity.createdOn = Instant.now();
        entity.persist();

        LOG.debugf("Persisted event %s: %s [%s]", entity.id, event.type().value(), event.ref());

        persistedEvents.fire(new StreamEventPersisted(
                entity.id.toString(), event.type().value(), event.eventSourceId()));

        return errors.isEmpty() ? PersistOutcome.PERSISTED : PersistOutcome.PERSISTED_INVALID;
    }

    private List<String> validationErrors(NormalizedEvent event) {
        try {
            return schemaRegistry.validate(event);
        } catch (RuntimeException e) {
            return List.of("Schema validation failed: " + e.getMessage());
        }
    }

    private String toJson(List<String> errors) {
        try {
            return objectMapper.writeValueAsString(errors);
        } catch (Exception e) {
            return "[\"" + String.join("; ", errors).replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
        }
    }
}
