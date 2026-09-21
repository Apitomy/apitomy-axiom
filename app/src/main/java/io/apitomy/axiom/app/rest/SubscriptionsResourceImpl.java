package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.SubscriptionsResource;
import io.apitomy.axiom.api.beans.Actor;
import io.apitomy.axiom.api.beans.NewSubscription;
import io.apitomy.axiom.api.beans.Payload;
import io.apitomy.axiom.api.beans.SourceData;
import io.apitomy.axiom.api.beans.StreamEvent;
import io.apitomy.axiom.api.beans.Subscription;
import io.apitomy.axiom.api.beans.SubscriptionPreviewRequest;
import io.apitomy.axiom.api.beans.SubscriptionPreviewResponse;
import io.apitomy.axiom.api.beans.SubscriptionPreviewResult;
import io.apitomy.axiom.api.beans.SubscriptionSearchResults;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.filters.SubscriptionFilterEvaluator;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the Subscriptions REST API.
 */
@ApplicationScoped
@RunOnVirtualThread
public class SubscriptionsResourceImpl implements SubscriptionsResource {

    private static final Logger LOG = Logger.getLogger(SubscriptionsResourceImpl.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SubscriptionFilterEvaluator filterEvaluator;

    /**
     * {@inheritDoc}
     */
    @Override
    public SubscriptionSearchResults listSubscriptions(BigInteger page, BigInteger limit,
                                                       String filterName) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterName != null && !filterName.isBlank()) {
            hql.append(" and (lower(name) like :name or lower(description) like :name)");
            params.put("name", "%" + filterName.toLowerCase() + "%");
        }

        long totalCount = EventSubscriptionEntity.count(hql.toString(), params);
        List<Subscription> items = EventSubscriptionEntity.<EventSubscriptionEntity>find(
                        hql.toString(), Sort.ascending("name"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list().stream().map(this::toBean).toList();

        SubscriptionSearchResults results = new SubscriptionSearchResults();
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
    public Subscription createSubscription(NewSubscription data) {
        EventSubscriptionEntity entity = new EventSubscriptionEntity();
        applyFields(entity, data);
        Instant now = Instant.now();
        entity.createdOn = now;
        entity.modifiedOn = now;
        entity.persist();
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Subscription getSubscription(long subscriptionId) {
        return toBean(findOrThrow(subscriptionId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public Subscription updateSubscription(long subscriptionId, NewSubscription data) {
        EventSubscriptionEntity entity = findOrThrow(subscriptionId);
        applyFields(entity, data);
        entity.modifiedOn = Instant.now();
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteSubscription(long subscriptionId) {
        EventSubscriptionEntity entity = findOrThrow(subscriptionId);
        entity.delete();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SubscriptionPreviewResponse previewSubscriptionFilter(SubscriptionPreviewRequest data) {
        int pageNum = data.getPage() != null ? data.getPage() : 1;
        int pageSize = data.getLimit() != null ? data.getLimit() : 20;
        String filterExpression = data.getFilterExpression();

        // Query total count
        long totalCount = StreamEventEntity.count();

        // Fetch paginated events ordered by timestamp DESC (newest first)
        List<StreamEventEntity> entities = StreamEventEntity.<StreamEventEntity>find(
                        "1=1", Sort.descending("timestamp"))
                .page(Page.of(pageNum - 1, pageSize))
                .list();

        // Evaluate each event against the filter expression
        List<SubscriptionPreviewResult> results = new ArrayList<>();
        long totalMatched = 0;

        for (StreamEventEntity entity : entities) {
            // Build the event map for filter evaluation (same as EventStreamOrchestrator)
            Map<String, Object> eventMap = buildEventMap(entity);
            boolean matched = filterEvaluator.matches(filterExpression, eventMap);

            SubscriptionPreviewResult result = new SubscriptionPreviewResult();
            result.setEvent(toStreamEventBean(entity));
            result.setMatched(matched);
            results.add(result);

            if (matched) {
                totalMatched++;
            }
        }

        // If there are more pages, we need to count total matched across all events.
        // For efficiency, if we're on the only page, use the local count.
        // Otherwise, iterate through all events to count matches.
        if (totalCount > pageSize) {
            // Count total matched across all events
            totalMatched = countTotalMatched(filterExpression);
        }

        SubscriptionPreviewResponse response = new SubscriptionPreviewResponse();
        response.setResults(results);
        response.setTotalCount(totalCount);
        response.setTotalMatched(totalMatched);
        response.setPage(pageNum);
        response.setLimit(pageSize);
        return response;
    }

    /**
     * Counts the total number of events matching the filter expression across all events.
     */
    private long countTotalMatched(String filterExpression) {
        if (filterExpression == null || filterExpression.isBlank()) {
            return StreamEventEntity.count();
        }

        long matched = 0;
        int batchSize = 100;
        int batchIndex = 0;
        List<StreamEventEntity> batch;

        do {
            batch = StreamEventEntity.<StreamEventEntity>find("1=1", Sort.descending("timestamp"))
                    .page(Page.of(batchIndex, batchSize))
                    .list();
            for (StreamEventEntity entity : batch) {
                Map<String, Object> eventMap = buildEventMap(entity);
                if (filterEvaluator.matches(filterExpression, eventMap)) {
                    matched++;
                }
            }
            batchIndex++;
        } while (batch.size() == batchSize);

        return matched;
    }

    /**
     * Builds the event map used for filter evaluation, compatible with
     * {@link io.apitomy.axiom.app.EventStreamOrchestrator#buildEventMap}.
     */
    private Map<String, Object> buildEventMap(StreamEventEntity event) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", event.type);
        map.put("source", event.source);
        map.put("connectionId", event.connectionId);
        map.put("ref", event.ref);
        map.put("timestamp", event.timestamp.toString());

        // Parse actor
        if (event.actor != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> actor = objectMapper.readValue(event.actor, Map.class);
                map.put("actor", actor);
            } catch (Exception e) {
                map.put("actor", Map.of());
            }
        }

        // Parse payload
        if (event.payload != null) {
            try {
                JsonNode payloadNode = objectMapper.readTree(event.payload);
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = objectMapper.convertValue(payloadNode, Map.class);
                map.put("payload", payload);
            } catch (Exception e) {
                map.put("payload", Map.of());
            }
        } else {
            map.put("payload", Map.of());
        }

        return map;
    }

    /**
     * Converts a StreamEventEntity to a StreamEvent API bean.
     * Mirrors the logic in {@link StreamEventsResourceImpl#toBean}.
     */
    private StreamEvent toStreamEventBean(StreamEventEntity entity) {
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

    /**
     * Applies field values from the API bean to the entity.
     *
     * @param entity the entity to update
     * @param data the API bean with new values
     */
    private void applyFields(EventSubscriptionEntity entity, NewSubscription data) {
        entity.name = data.getName();
        entity.description = data.getDescription();
        entity.enabled = data.getEnabled() != null ? data.getEnabled() : false;
        if (data.getFilterExpression() != null && !data.getFilterExpression().isBlank()) {
            if (!filterEvaluator.isValid(data.getFilterExpression())) {
                throw new WebApplicationException(
                        "Invalid filter expression: " + data.getFilterExpression(), 400);
            }
            entity.filters = data.getFilterExpression();
        } else {
            entity.filters = data.getFilterExpression(); // null or blank
        }
        entity.labels.clear();
        if (data.getLabels() != null) {
            entity.labels.addAll(data.getLabels());
        }

        // Routing rules
        if (data.getRouting() != null) {
            // Validate routing rules
            for (io.apitomy.axiom.api.beans.RoutingRule rule : data.getRouting()) {
                if (rule.getType() == null) {
                    throw new WebApplicationException("Routing rule missing required 'type' field", 400);
                }
                switch (rule.getType()) {
                    case CREATE_WORKFLOW -> {
                        if (rule.getWorkflowDefinitionId() == null) {
                            throw new WebApplicationException(
                                    "create-workflow routing rule requires workflowDefinitionId", 400);
                        }
                    }
                    case INVOKE_ACTION -> {
                        if (rule.getActionTypeId() == null) {
                            throw new WebApplicationException(
                                    "invoke-action routing rule requires actionTypeId", 400);
                        }
                    }
                    default -> { /* manager and workflow-dispatch need no extra config */ }
                }
            }
            try {
                entity.routing = objectMapper.writeValueAsString(data.getRouting());
            } catch (Exception e) {
                throw new WebApplicationException("Failed to serialize routing rules", 500);
            }
        } else {
            entity.routing = null;
        }
    }

    /**
     * Finds a subscription by ID or throws a 404 WebApplicationException.
     *
     * @param id the subscription ID
     * @return the entity
     */
    private EventSubscriptionEntity findOrThrow(long id) {
        EventSubscriptionEntity entity = EventSubscriptionEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Subscription not found: " + id, 404);
        }
        return entity;
    }

    /**
     * Converts an entity to an API bean.
     *
     * @param entity the entity to convert
     * @return the API bean
     */
    private Subscription toBean(EventSubscriptionEntity entity) {
        Subscription bean = new Subscription();
        bean.setId(entity.id);
        bean.setName(entity.name);
        bean.setDescription(entity.description);
        bean.setEnabled(entity.enabled);
        bean.setLabels(entity.labels);
        bean.setFilterExpression(entity.filters);
        if (entity.routing != null && !entity.routing.isBlank()) {
            try {
                bean.setRouting(objectMapper.readValue(entity.routing,
                        objectMapper.getTypeFactory().constructCollectionType(List.class,
                                io.apitomy.axiom.api.beans.RoutingRule.class)));
            } catch (Exception e) {
                LOG.warnf("Failed to parse routing JSON for subscription %d: %s",
                        entity.id, e.getMessage());
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
