package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.EventResource;
import io.apitomy.axiom.api.beans.EventSource;
import io.apitomy.axiom.api.beans.EventSourceLog;
import io.apitomy.axiom.api.beans.EventSourceLogSearchResults;
import io.apitomy.axiom.api.beans.NewEventSource;
import io.apitomy.axiom.core.entities.EventSourceEntity;
import io.apitomy.axiom.core.entities.EventSourceLogEntity;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the Event Sources REST API.
 */
@ApplicationScoped
@RunOnVirtualThread
public class EventSourcesResourceImpl implements EventResource {

    @Inject
    ObjectMapper objectMapper;

    /**
     * {@inheritDoc}
     */
    @Override
    public List<EventSource> listEventSources() {
        return EventSourceEntity.<EventSourceEntity>listAll()
                .stream()
                .sorted(Comparator.comparing(e -> e.name))
                .map(this::toBean)
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public EventSource createEventSource(NewEventSource data) {
        EventSourceEntity entity = new EventSourceEntity();
        applyFields(entity, data);
        entity.persist();
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventSource getEventSource(long eventSourceId) {
        return toBean(findOrThrow(eventSourceId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public EventSource updateEventSource(long eventSourceId, NewEventSource data) {
        EventSourceEntity entity = findOrThrow(eventSourceId);
        applyFields(entity, data);
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteEventSource(long eventSourceId) {
        EventSourceEntity entity = findOrThrow(eventSourceId);
        entity.delete();
    }

    /**
     * Applies field values from the API bean to the entity.
     *
     * @param entity the entity to update
     * @param data the API bean with new values
     */
    private void applyFields(EventSourceEntity entity, NewEventSource data) {
        entity.name = data.getName();
        entity.description = data.getDescription();
        entity.sourceType = data.getSourceType() != null ? data.getSourceType().value() : "github";
        entity.enabled = data.getEnabled() != null ? data.getEnabled() : false;
        entity.pollInterval = data.getPollInterval();
        entity.secretName = data.getSecretName();
        if (data.getConfiguration() != null) {
            try {
                entity.configuration = objectMapper.writeValueAsString(data.getConfiguration());
            } catch (Exception e) {
                entity.configuration = "{}";
            }
        } else {
            entity.configuration = "{}";
        }
        entity.labels.clear();
        if (data.getLabels() != null) {
            entity.labels.addAll(data.getLabels());
        }
    }

    /**
     * Finds an event source by ID or throws a 404 WebApplicationException.
     *
     * @param id the event source ID
     * @return the entity
     */
    private EventSourceEntity findOrThrow(long id) {
        EventSourceEntity entity = EventSourceEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Event source not found: " + id, 404);
        }
        return entity;
    }

    /**
     * Converts an entity to an API bean.
     *
     * @param entity the entity to convert
     * @return the API bean
     */
    @SuppressWarnings("unchecked")
    private EventSource toBean(EventSourceEntity entity) {
        EventSource bean = new EventSource();
        bean.setId(entity.id);
        bean.setName(entity.name);
        bean.setDescription(entity.description);
        bean.setSourceType(EventSource.SourceType.fromValue(entity.sourceType));
        bean.setEnabled(entity.enabled);
        bean.setPollInterval(entity.pollInterval);
        bean.setSecretName(entity.secretName);
        if (entity.configuration != null) {
            try {
                bean.setConfiguration(objectMapper.readValue(entity.configuration,
                        io.apitomy.axiom.api.beans.Configuration.class));
            } catch (Exception e) {
                // ignore parse errors
            }
        }
        bean.setLabels(entity.labels);
        return bean;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventSourceLogSearchResults listEventSourceLogs(BigInteger eventSourceId,
                                                            BigInteger page, BigInteger limit) {
        findOrThrow(eventSourceId.longValue());
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        long totalCount = EventSourceLogEntity.count("eventSourceId", eventSourceId.longValue());
        List<EventSourceLog> items = EventSourceLogEntity.<EventSourceLogEntity>find(
                        "eventSourceId", Sort.descending("createdOn"),
                        eventSourceId.longValue())
                .page(Page.of(pageNum - 1, pageSize))
                .list().stream()
                .map(this::toLogBean)
                .toList();

        EventSourceLogSearchResults results = new EventSourceLogSearchResults();
        results.setItems(items);
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    private EventSourceLog toLogBean(EventSourceLogEntity entity) {
        EventSourceLog log = new EventSourceLog();
        log.setId(entity.id);
        log.setEventSourceId(entity.eventSourceId);
        log.setStatus(entity.status);
        log.setMessage(entity.message);
        log.setDetail(entity.detail);
        log.setEventsIngested(entity.eventsIngested);
        log.setCreatedOn(Date.from(entity.createdOn));
        return log;
    }
}
