package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.StreamResource;
import io.apitomy.axiom.api.beans.Actor;
import io.apitomy.axiom.api.beans.EventProcessingEntry;
import io.apitomy.axiom.api.beans.EventProcessingSearchResults;
import io.apitomy.axiom.api.beans.Payload;
import io.apitomy.axiom.api.beans.SourceData;
import io.apitomy.axiom.api.beans.StreamEvent;
import io.apitomy.axiom.api.beans.StreamEventSearchResults;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.jboss.logging.Logger;

import java.math.BigInteger;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementation of the Stream Events REST API. Provides read-only,
 * paginated, filterable access to the normalized event stream.
 */
@ApplicationScoped
@RunOnVirtualThread
public class StreamEventsResourceImpl implements StreamResource {

    private static final Logger LOG = Logger.getLogger(StreamEventsResourceImpl.class);

    @Inject
    ObjectMapper objectMapper;

    @Override
    public StreamEventSearchResults listStreamEvents(BigInteger page, BigInteger limit,
                                                     String filterType, String filterConnectionId,
                                                     String filterRef) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterType != null && !filterType.isBlank()) {
            hql.append(" and lower(type) like :type");
            params.put("type", filterType.toLowerCase() + "%");
        }
        if (filterConnectionId != null && !filterConnectionId.isBlank()) {
            hql.append(" and connectionId = :connectionId");
            params.put("connectionId", filterConnectionId);
        }
        if (filterRef != null && !filterRef.isBlank()) {
            hql.append(" and lower(ref) like :ref");
            params.put("ref", "%" + filterRef.toLowerCase() + "%");
        }

        long totalCount = StreamEventEntity.count(hql.toString(), params);
        List<StreamEventEntity> entities = StreamEventEntity.<StreamEventEntity>find(
                        hql.toString(), Sort.descending("timestamp"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list();

        List<StreamEvent> items = entities.stream()
                .map(this::toBean)
                .toList();

        StreamEventSearchResults results = new StreamEventSearchResults();
        results.setItems(items);
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    @Override
    public StreamEvent getStreamEvent(String eventId) {
        UUID id;
        try {
            id = UUID.fromString(eventId);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("Invalid event ID: " + eventId, 404);
        }
        StreamEventEntity entity = StreamEventEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Stream event not found: " + eventId, 404);
        }
        return toBean(entity);
    }

    @Override
    public EventProcessingSearchResults listEventProcessing(String eventId) {
        UUID uuid;
        try {
            uuid = UUID.fromString(eventId);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("Invalid event ID: " + eventId, 400);
        }

        List<EventProcessingLedgerEntity> entries = EventProcessingLedgerEntity
                .find("eventId = ?1 ORDER BY createdOn ASC", uuid).list();

        // Load subscription names in batch
        List<Long> subIds = entries.stream().map(e -> e.subscriptionId).distinct().toList();
        Map<Long, String> subNames = new HashMap<>();
        if (!subIds.isEmpty()) {
            List<EventSubscriptionEntity> subs = EventSubscriptionEntity
                    .find("id in ?1", subIds).list();
            for (EventSubscriptionEntity sub : subs) {
                subNames.put(sub.id, sub.name);
            }
        }

        List<EventProcessingEntry> items = entries.stream().map(e -> {
            EventProcessingEntry entry = new EventProcessingEntry();
            entry.setId(e.id);
            entry.setSubscriptionId(e.subscriptionId);
            entry.setSubscriptionName(subNames.getOrDefault(e.subscriptionId,
                    "Subscription #" + e.subscriptionId));
            entry.setStatus(e.status);
            entry.setErrorMessage(e.errorMessage);
            if (e.createdOn != null) entry.setCreatedOn(Date.from(e.createdOn));
            if (e.processedOn != null) entry.setProcessedOn(Date.from(e.processedOn));
            return entry;
        }).toList();

        EventProcessingSearchResults results = new EventProcessingSearchResults();
        results.setItems(items);
        results.setTotalCount((long) items.size());
        return results;
    }

    private StreamEvent toBean(StreamEventEntity entity) {
        StreamEvent bean = new StreamEvent();
        bean.setId(entity.id);
        bean.setSourceEventId(entity.sourceEventId);
        bean.setSource(entity.source);
        bean.setConnectionId(entity.connectionId);
        bean.setType(entity.type);
        bean.setRef(entity.ref);
        bean.setTimestamp(Date.from(entity.timestamp));
        bean.setCreatedOn(Date.from(entity.createdOn));

        try {
            bean.setActor(objectMapper.readValue(entity.actor, Actor.class));
        } catch (Exception e) {
            LOG.warnf("Failed to parse actor JSON for event %s: %s", entity.id, e.getMessage());
        }

        try {
            bean.setPayload(objectMapper.readValue(entity.payload, Payload.class));
        } catch (Exception e) {
            LOG.warnf("Failed to parse payload JSON for event %s: %s", entity.id, e.getMessage());
        }

        if (entity.sourceData != null) {
            try {
                bean.setSourceData(objectMapper.readValue(entity.sourceData, SourceData.class));
            } catch (Exception e) {
                LOG.warnf("Failed to parse sourceData JSON for event %s: %s", entity.id, e.getMessage());
            }
        }

        return bean;
    }
}
