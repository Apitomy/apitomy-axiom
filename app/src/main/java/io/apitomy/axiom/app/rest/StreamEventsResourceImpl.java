package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.StreamResource;
import io.apitomy.axiom.api.beans.Actor;
import io.apitomy.axiom.api.beans.EventProcessingEntry;
import io.apitomy.axiom.api.beans.EventProcessingOutcome;
import io.apitomy.axiom.api.beans.EventProcessingOutcomeItem;
import io.apitomy.axiom.api.beans.EventProcessingSearchResults;
import io.apitomy.axiom.api.beans.Payload;
import io.apitomy.axiom.api.beans.SourceData;
import io.apitomy.axiom.api.beans.StreamEvent;
import io.apitomy.axiom.api.beans.StreamEventSearchResults;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
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
import java.util.Objects;
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

        // Batch-load matched subscription counts from the processing ledger
        List<UUID> eventIds = entities.stream().map(e -> e.id).toList();
        Map<UUID, Integer> matchCounts = new HashMap<>();
        if (!eventIds.isEmpty()) {
            // Count completed ledger entries per event
            List<EventProcessingLedgerEntity> completedEntries = EventProcessingLedgerEntity
                    .find("eventId IN ?1 AND status = 'completed'", eventIds).list();
            for (EventProcessingLedgerEntity entry : completedEntries) {
                matchCounts.merge(entry.eventId, 1, Integer::sum);
            }
        }

        List<StreamEvent> items = entities.stream()
                .map(e -> {
                    StreamEvent bean = toBean(e);
                    bean.setMatchedSubscriptions(matchCounts.getOrDefault(e.id, 0));
                    return bean;
                })
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

        // Load subscriptions in batch (for names and routing rules)
        List<Long> subIds = entries.stream().map(e -> e.subscriptionId).distinct().toList();
        Map<Long, EventSubscriptionEntity> subsById = new HashMap<>();
        if (!subIds.isEmpty()) {
            List<EventSubscriptionEntity> subs = EventSubscriptionEntity
                    .find("id in ?1", subIds).list();
            for (EventSubscriptionEntity sub : subs) {
                subsById.put(sub.id, sub);
            }
        }

        // Load routing outcomes for completed entries
        List<Long> completedLedgerIds = entries.stream()
                .filter(e -> "completed".equals(e.status))
                .map(e -> e.id).toList();
        Map<Long, List<RoutingOutcomeEntity>> outcomesByLedger = new HashMap<>();
        if (!completedLedgerIds.isEmpty()) {
            List<RoutingOutcomeEntity> allOutcomes = RoutingOutcomeEntity
                    .find("ledgerId IN ?1 ORDER BY createdOn ASC", completedLedgerIds).list();
            for (RoutingOutcomeEntity o : allOutcomes) {
                outcomesByLedger.computeIfAbsent(o.ledgerId, k -> new java.util.ArrayList<>()).add(o);
            }
        }

        // Load the items of those outcomes
        List<Long> outcomeIds = outcomesByLedger.values().stream()
                .flatMap(List::stream).map(o -> o.id).toList();
        Map<Long, List<RoutingOutcomeItemEntity>> itemsByOutcome = new HashMap<>();
        if (!outcomeIds.isEmpty()) {
            RoutingOutcomeItemEntity.<RoutingOutcomeItemEntity>list(
                    "outcomeId IN ?1 ORDER BY id ASC", outcomeIds)
                    .forEach(i -> itemsByOutcome
                            .computeIfAbsent(i.outcomeId, k -> new java.util.ArrayList<>()).add(i));
        }

        // Load project names for outcome and item project IDs
        java.util.Set<Long> outcomeProjectIds = new java.util.HashSet<>();
        for (List<RoutingOutcomeEntity> ocs : outcomesByLedger.values()) {
            for (RoutingOutcomeEntity o : ocs) {
                if (o.projectId != null) outcomeProjectIds.add(o.projectId);
            }
        }
        itemsByOutcome.values().stream().flatMap(List::stream)
                .filter(i -> i.projectId != null)
                .forEach(i -> outcomeProjectIds.add(i.projectId));
        Map<Long, String> projectNames = new HashMap<>();
        if (!outcomeProjectIds.isEmpty()) {
            List<ProjectEntity> projects = ProjectEntity
                    .find("id IN ?1", new java.util.ArrayList<>(outcomeProjectIds)).list();
            for (ProjectEntity p : projects) {
                projectNames.put(p.id, p.name);
            }
        }

        // Load task statuses for outcome task IDs
        java.util.Set<Long> outcomeTaskIds = new java.util.HashSet<>();
        for (List<RoutingOutcomeEntity> ocs : outcomesByLedger.values()) {
            for (RoutingOutcomeEntity o : ocs) {
                if (o.taskId != null) outcomeTaskIds.add(o.taskId);
            }
        }
        itemsByOutcome.values().stream().flatMap(List::stream)
                .filter(i -> i.taskId != null)
                .forEach(i -> outcomeTaskIds.add(i.taskId));
        Map<Long, String> taskStatuses = new HashMap<>();
        if (!outcomeTaskIds.isEmpty()) {
            List<TaskEntity> tasks = TaskEntity
                    .find("id IN ?1", new java.util.ArrayList<>(outcomeTaskIds)).list();
            for (TaskEntity t : tasks) {
                taskStatuses.put(t.id, t.status);
            }
        }

        // Load the traces of the workflow runs the items started or resumed
        List<Long> itemRunIds = itemsByOutcome.values().stream().flatMap(List::stream)
                .map(i -> i.workflowRunId).filter(Objects::nonNull).distinct().toList();
        Map<Long, UUID> runTraceIds = new HashMap<>();
        if (!itemRunIds.isEmpty()) {
            WorkflowRunEntity.<WorkflowRunEntity>list("id IN ?1", itemRunIds).stream()
                    .filter(r -> r.traceId != null)
                    .forEach(r -> runTraceIds.put(r.id, r.traceId));
        }

        List<EventProcessingEntry> items = entries.stream().map(e -> {
            EventProcessingEntry entry = new EventProcessingEntry();
            entry.setId(e.id);
            entry.setSubscriptionId(e.subscriptionId);
            EventSubscriptionEntity sub = subsById.get(e.subscriptionId);
            entry.setSubscriptionName(sub != null ? sub.name : "Subscription #" + e.subscriptionId);
            entry.setStatus(e.status);
            entry.setErrorMessage(e.errorMessage);
            if (e.createdOn != null) entry.setCreatedOn(Date.from(e.createdOn));
            if (e.processedOn != null) entry.setProcessedOn(Date.from(e.processedOn));

            // Add routing rules from subscription
            if (sub != null && sub.routing != null && !sub.routing.isBlank()) {
                try {
                    List<io.apitomy.axiom.api.beans.RoutingRule> rules = objectMapper.readValue(
                            sub.routing, objectMapper.getTypeFactory().constructCollectionType(
                                    List.class, io.apitomy.axiom.api.beans.RoutingRule.class));
                    entry.setRoutingRules(rules);
                } catch (Exception ex) {
                    LOG.warnf("Failed to parse routing rules for subscription %d", e.subscriptionId);
                }
            }

            // Add outcomes from routing_outcome table
            List<RoutingOutcomeEntity> entryOutcomes = outcomesByLedger
                    .getOrDefault(e.id, List.of());
            List<EventProcessingOutcome> outcomes = entryOutcomes.stream().map(o -> {
                EventProcessingOutcome outcome = new EventProcessingOutcome();
                outcome.setType(o.routingType);
                outcome.setSummary(o.summary);
                outcome.setProjectId(o.projectId);
                if (o.projectId != null) outcome.setProjectName(projectNames.get(o.projectId));
                outcome.setTaskId(o.taskId);
                if (o.taskId != null) outcome.setTaskStatus(taskStatuses.get(o.taskId));
                outcome.setTraceId(o.traceId);
                outcome.setItems(itemsByOutcome.getOrDefault(o.id, List.of()).stream()
                        .map(i -> toItemBean(i, projectNames, taskStatuses, runTraceIds))
                        .toList());
                return outcome;
            }).toList();
            if (!outcomes.isEmpty()) {
                entry.setOutcomes(outcomes);
            }

            return entry;
        }).toList();

        EventProcessingSearchResults results = new EventProcessingSearchResults();
        results.setItems(items);
        results.setTotalCount((long) items.size());
        return results;
    }

    private static EventProcessingOutcomeItem toItemBean(RoutingOutcomeItemEntity i,
                                                         Map<Long, String> projectNames,
                                                         Map<Long, String> taskStatuses,
                                                         Map<Long, UUID> runTraceIds) {
        EventProcessingOutcomeItem item = new EventProcessingOutcomeItem();
        item.setType(i.itemType);
        item.setStatus(i.status);
        item.setSummary(i.summary);
        item.setErrorMessage(i.errorMessage);
        item.setProjectId(i.projectId);
        if (i.projectId != null) item.setProjectName(projectNames.get(i.projectId));
        item.setTaskId(i.taskId);
        if (i.taskId != null) item.setTaskStatus(taskStatuses.get(i.taskId));
        item.setWorkflowRunId(i.workflowRunId);
        if (i.workflowRunId != null) item.setTraceId(runTraceIds.get(i.workflowRunId));
        item.setTraceNodeId(i.traceNodeId);
        return item;
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
