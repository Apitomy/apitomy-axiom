package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.api.EventResource;
import io.apitomy.axiom.api.beans.EventSource;
import io.apitomy.axiom.api.beans.EventSourcePollLog;
import io.apitomy.axiom.api.beans.EventSourcePollLogSearchResults;
import io.apitomy.axiom.api.beans.EventSourceSearchResults;
import io.apitomy.axiom.api.beans.EventSourceStatus;
import io.apitomy.axiom.api.beans.Configuration;
import io.apitomy.axiom.api.beans.NewEventSource;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.app.validation.ConfigItemValidationService;
import io.apitomy.axiom.app.validation.ConfigProblem;
import io.apitomy.axiom.app.validation.ValidationContextFactory;
import io.apitomy.axiom.core.entities.EventSourcePollLogEntity;
import io.apitomy.axiom.core.entities.EventSourceEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import org.jboss.logging.Logger;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the Event Sources REST API.
 */
@ApplicationScoped
@RunOnVirtualThread
public class EventSourcesResourceImpl implements EventResource {

    private static final Logger LOG = Logger.getLogger(EventSourcesResourceImpl.class);

    private static final int SLUG_MAX_LENGTH = 63;
    private static final String INVALID_ID_MESSAGE =
            "Invalid event source id: must be 1-63 lowercase letters, numbers, or dashes";

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ConfigItemValidationService validationService;

    @Inject
    ValidationContextFactory contextFactory;

    /**
     * {@inheritDoc}
     */
    @Override
    public EventSourceSearchResults listEventSources(BigInteger page, BigInteger limit,
                                                   String filterName, String filterType) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterName != null && !filterName.isBlank()) {
            hql.append(" and (lower(name) like :name or lower(description) like :name)");
            params.put("name", "%" + filterName.toLowerCase() + "%");
        }

        if (filterType != null && !filterType.isBlank()) {
            hql.append(" and sourceType = :sourceType");
            params.put("sourceType", filterType.toLowerCase());
        }

        long totalCount = EventSourceEntity.count(hql.toString(), params);
        List<EventSource> items = EventSourceEntity.<EventSourceEntity>find(
                        hql.toString(), Sort.ascending("name"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list().stream().map(this::toBean).toList();

        EventSourceSearchResults results = new EventSourceSearchResults();
        results.setItems(items);
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public EventSource createEventSource(NewEventSource data) {
        String slug = data.getId();
        if (slug != null && slug.length() > SLUG_MAX_LENGTH) {
            throw new WebApplicationException(INVALID_ID_MESSAGE, 400);
        }
        validate(data, slug);

        EventSourceEntity existing = EventSourceEntity.findById(slug);
        if (existing != null) {
            throw new WebApplicationException("Event source already exists: " + slug, 409);
        }

        EventSourceEntity entity = new EventSourceEntity();
        entity.id = slug;
        Instant now = Instant.now();
        entity.createdOn = now;
        entity.modifiedOn = now;
        applyFields(entity, data);
        entity.persist();
        return toBean(entity);
    }

    /**
     * Validates the event source with the shared configuration validation service and throws a 400 with the
     * first error message, if any. Id errors keep the endpoint's historical message.
     *
     * @param data the API bean to validate
     * @param id the event source id to validate against
     */
    private void validate(NewEventSource data, String id) {
        ObjectNode item = objectMapper.valueToTree(data);
        if (id != null) {
            item.put("id", id);
        }
        List<ConfigProblem> problems = validationService.validate(ConfigItemType.EVENT_SOURCE, item,
                contextFactory.databaseOnly());
        problems.stream().filter(ConfigProblem::isError).findFirst().ifPresent(p -> {
            String message = "id".equals(p.path()) ? INVALID_ID_MESSAGE : p.message();
            throw new WebApplicationException(message, 400);
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventSource getEventSource(String eventSourceId) {
        return toBean(findOrThrow(eventSourceId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public EventSource updateEventSource(String eventSourceId, NewEventSource data) {
        EventSourceEntity entity = findOrThrow(eventSourceId);
        validate(data, eventSourceId);
        entity.modifiedOn = Instant.now();
        applyFields(entity, data);
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteEventSource(String eventSourceId) {
        EventSourceEntity entity = findOrThrow(eventSourceId);
        entity.delete();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventSourceStatus getEventSourceStatus(String eventSourceId) {
        EventSourceEntity entity = findOrThrow(eventSourceId);

        long totalEvents = StreamEventEntity.count("eventSourceId", eventSourceId);

        EventSourceStatus status = new EventSourceStatus();
        status.setEventSourceId(eventSourceId);
        status.setEnabled(entity.enabled);
        status.setTotalEventsProduced(totalEvents);
        if (entity.lastPolledAt != null) {
            status.setLastPolledAt(Date.from(entity.lastPolledAt));
        }
        // Find last error from poll logs
        EventSourcePollLogEntity lastError = EventSourcePollLogEntity.<EventSourcePollLogEntity>find(
                "eventSourceId = ?1 and status = 'error' ORDER BY createdOn DESC", eventSourceId)
                .firstResult();
        if (lastError != null) {
            status.setLastError(lastError.message);
            status.setLastErrorAt(Date.from(lastError.createdOn));
        } else {
            status.setLastError(null);
            status.setLastErrorAt(null);
        }
        return status;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EventSourcePollLogSearchResults listEventSourcePollLogs(String eventSourceId,
            BigInteger page, BigInteger limit) {
        findOrThrow(eventSourceId);
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        long totalCount = EventSourcePollLogEntity.count("eventSourceId", eventSourceId);
        List<EventSourcePollLog> items = EventSourcePollLogEntity.<EventSourcePollLogEntity>find(
                        "eventSourceId = ?1 ORDER BY createdOn DESC", eventSourceId)
                .page(Page.of(pageNum - 1, pageSize))
                .list().stream().map(this::toPollLogBean).toList();

        EventSourcePollLogSearchResults results = new EventSourcePollLogSearchResults();
        results.setItems(items);
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    private EventSourcePollLog toPollLogBean(EventSourcePollLogEntity entity) {
        EventSourcePollLog bean = new EventSourcePollLog();
        bean.setId(entity.id);
        bean.setEventSourceId(entity.eventSourceId);
        bean.setStatus(entity.status);
        bean.setMessage(entity.message);
        bean.setDetail(entity.detail);
        bean.setEventsIngested(entity.eventsIngested);
        bean.setDurationMs(entity.durationMs);
        if (entity.createdOn != null) {
            bean.setCreatedOn(Date.from(entity.createdOn));
        }
        return bean;
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
        entity.baseUrl = data.getBaseUrl();
        entity.secretName = data.getSecretName();
        entity.pollInterval = data.getPollInterval();
        if (data.getConfiguration() != null) {
            try {
                entity.configuration = objectMapper.writeValueAsString(data.getConfiguration());
            } catch (Exception e) {
                entity.configuration = "{}";
            }
        } else {
            entity.configuration = "{}";
        }
    }

    /**
     * Finds an event source by ID or throws a 404 WebApplicationException.
     *
     * @param id the event source ID (slug)
     * @return the entity
     */
    private EventSourceEntity findOrThrow(String id) {
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
    private EventSource toBean(EventSourceEntity entity) {
        EventSource bean = new EventSource();
        bean.setId(entity.id);
        bean.setName(entity.name);
        bean.setDescription(entity.description);
        bean.setSourceType(EventSource.SourceType.fromValue(entity.sourceType));
        bean.setEnabled(entity.enabled);
        bean.setBaseUrl(entity.baseUrl);
        bean.setSecretName(entity.secretName);
        bean.setPollInterval(entity.pollInterval);
        if (entity.configuration != null) {
            try {
                bean.setConfiguration(objectMapper.readValue(entity.configuration,
                        Configuration.class));
            } catch (Exception e) {
                // ignore parse errors
            }
        }
        if (entity.createdOn != null) {
            bean.setCreatedOn(Date.from(entity.createdOn));
        }
        if (entity.modifiedOn != null) {
            bean.setModifiedOn(Date.from(entity.modifiedOn));
        }
        return bean;
    }
}
