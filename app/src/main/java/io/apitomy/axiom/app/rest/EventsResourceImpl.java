package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.EventsResource;
import io.apitomy.axiom.api.beans.Event;
import io.apitomy.axiom.api.beans.EventSearchResults;
import io.apitomy.axiom.api.beans.NewEvent;
import io.apitomy.axiom.api.beans.Trace;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.EventSourceEntity;
import io.apitomy.axiom.events.core.EventService;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementation of the Events REST API. Provides paginated, filterable
 * access to raw events received by the system.
 */
@ApplicationScoped
@RunOnVirtualThread
public class EventsResourceImpl implements EventsResource {

    @Inject
    EventService eventService;

    /**
     * {@inheritDoc}
     */
    @Override
    public EventSearchResults listEvents(BigInteger page, BigInteger limit,
                                          String filterSource, String filterEventType,
                                          String filterRepository, String filterLabels,
                                          String filterFilterStatus, BigInteger filterEventSourceId) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterSource != null && !filterSource.isBlank()) {
            hql.append(" and lower(source) like :source");
            params.put("source", "%" + filterSource.toLowerCase() + "%");
        }
        if (filterEventType != null && !filterEventType.isBlank()) {
            hql.append(" and lower(eventType) like :eventType");
            params.put("eventType", "%" + filterEventType.toLowerCase() + "%");
        }
        if (filterRepository != null && !filterRepository.isBlank()) {
            hql.append(" and lower(repository) like :repository");
            params.put("repository", "%" + filterRepository.toLowerCase() + "%");
        }
        if (filterLabels != null && !filterLabels.isBlank()) {
            List<String> labels = Arrays.stream(filterLabels.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            hql.append(" and eventSourceId in (SELECT es.id FROM EventSourceEntity es"
                    + " JOIN es.labels esl WHERE esl IN :labels"
                    + " GROUP BY es.id HAVING COUNT(DISTINCT esl) = :labelCount)");
            params.put("labels", labels);
            params.put("labelCount", (long) labels.size());
        }
        if (filterFilterStatus != null && !filterFilterStatus.isBlank()) {
            hql.append(" and filterStatus = :filterStatus");
            params.put("filterStatus", filterFilterStatus);
        }
        if (filterEventSourceId != null) {
            hql.append(" and eventSourceId = :eventSourceId");
            params.put("eventSourceId", filterEventSourceId.longValue());
        }

        long totalCount = EventEntity.count(hql.toString(), params);
        List<EventEntity> entities = EventEntity.<EventEntity>find(
                        hql.toString(), Sort.descending("receivedAt"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list();

        Map<Long, List<String>> labelsMap = loadEventSourceLabels(entities);
        List<Event> items = entities.stream()
                .map(e -> toBean(e, labelsMap.getOrDefault(e.eventSourceId, Collections.emptyList())))
                .toList();

        EventSearchResults results = new EventSearchResults();
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
    public Event getEvent(long eventId) {
        EventEntity entity = EventEntity.findById(eventId);
        if (entity == null) {
            throw new WebApplicationException("Event not found: " + eventId, 404);
        }
        List<String> labels = lookupEventSourceLabels(entity.eventSourceId);
        return toBean(entity, labels);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Event fireEvent(NewEvent data) {
        EventEntity entity = eventService.ingestEvent(
                data.getSource(),
                data.getEventType(),
                data.getIssueRef(),
                data.getRepository(),
                data.getPayload());
        List<String> labels = lookupEventSourceLabels(entity.eventSourceId);
        return toBean(entity, labels);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Trace> listEventTraces(BigInteger eventId) {
        return TraceEntity.<TraceEntity>list("eventId", Sort.descending("startedOn"),
                eventId.longValue())
                .stream()
                .map(TraceMapper::toTraceBean)
                .toList();
    }

    /**
     * Batch-loads Event Source labels for a list of event entities.
     */
    private Map<Long, List<String>> loadEventSourceLabels(List<EventEntity> entities) {
        Set<Long> sourceIds = entities.stream()
                .map(e -> e.eventSourceId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        if (sourceIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return EventSourceEntity.<EventSourceEntity>list("id in ?1", List.copyOf(sourceIds))
                .stream()
                .collect(Collectors.toMap(es -> es.id, es -> es.labels));
    }

    /**
     * Looks up labels for a single Event Source by ID.
     */
    private List<String> lookupEventSourceLabels(Long eventSourceId) {
        if (eventSourceId == null) {
            return Collections.emptyList();
        }
        EventSourceEntity source = EventSourceEntity.findById(eventSourceId);
        return source != null ? source.labels : Collections.emptyList();
    }

    private Event toBean(EventEntity entity, List<String> labels) {
        Event event = new Event();
        event.setId(entity.id);
        event.setEventSourceId(entity.eventSourceId);
        event.setSource(entity.source);
        event.setEventType(entity.eventType);
        event.setIssueRef(entity.issueRef);
        event.setRepository(entity.repository);
        event.setProjectId(entity.projectId);
        event.setTaskId(entity.taskId);
        event.setPayload(entity.payload);
        event.setReceivedAt(Date.from(entity.receivedAt));
        event.setLabels(labels);
        if (entity.traceId != null) {
            event.setTraceId(entity.traceId);
        }
        event.setFilterStatus(entity.filterStatus);
        event.setFilterMatchedRule(entity.filterMatchedRule);
        return event;
    }
}
