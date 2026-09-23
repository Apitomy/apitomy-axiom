package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.StreamResource;
import io.apitomy.axiom.api.beans.Actor;
import io.apitomy.axiom.api.beans.EventProcessingEntry;
import io.apitomy.axiom.api.beans.EventProcessingOutcome;
import io.apitomy.axiom.api.beans.EventProcessingSearchResults;
import io.apitomy.axiom.api.beans.Payload;
import io.apitomy.axiom.api.beans.SourceData;
import io.apitomy.axiom.api.beans.StreamEvent;
import io.apitomy.axiom.api.beans.StreamEventSearchResults;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.events.model.RoutingRule;
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

        // Load the event to get its ref for looking up related projects
        StreamEventEntity event = StreamEventEntity.findById(uuid);
        String eventRef = event != null ? event.ref : null;

        // Look up projects and tasks linked to this event's ref
        Map<Long, ProjectEntity> projectsByRef = new HashMap<>();
        Map<Long, List<TaskEntity>> tasksByProject = new HashMap<>();
        if (eventRef != null) {
            List<ProjectEntity> projects = ProjectEntity.find("ref", eventRef).list();
            for (ProjectEntity p : projects) {
                projectsByRef.put(p.id, p);
                List<TaskEntity> tasks = TaskEntity
                        .find("projectId = ?1 and createdBy = 'manager' ORDER BY createdOn ASC", p.id)
                        .list();
                tasksByProject.put(p.id, tasks);
            }
        }

        // Look up activity log entries related to this event (by time window around processing)
        List<ActivityLogEntity> relatedActivities = new java.util.ArrayList<>();
        for (EventProcessingLedgerEntity e : entries) {
            if ("completed".equals(e.status) && e.processedOn != null) {
                // Find activities within 5 seconds of processing
                List<ActivityLogEntity> activities = ActivityLogEntity.find(
                        "entryType in ?1 and createdOn >= ?2 and createdOn <= ?3",
                        List.of("manager-evaluated", "event-ignored", "manager-escalation"),
                        e.processedOn.minusSeconds(5), e.processedOn.plusSeconds(5)).list();
                relatedActivities.addAll(activities);
            }
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

            // Add outcomes for completed entries
            if ("completed".equals(e.status)) {
                List<EventProcessingOutcome> outcomes = new java.util.ArrayList<>();

                // Manager decisions from activity log
                for (ActivityLogEntity activity : relatedActivities) {
                    EventProcessingOutcome outcome = new EventProcessingOutcome();
                    outcome.setType(activity.entryType);
                    outcome.setSummary(activity.summary);
                    if (activity.projectId != null) {
                        outcome.setProjectId(activity.projectId);
                        ProjectEntity proj = projectsByRef.get(activity.projectId);
                        if (proj != null) outcome.setProjectName(proj.name);
                    }
                    if (activity.taskId != null) {
                        outcome.setTaskId(activity.taskId);
                        // Find task status
                        for (List<TaskEntity> tasks : tasksByProject.values()) {
                            for (TaskEntity t : tasks) {
                                if (t.id.equals(activity.taskId)) {
                                    outcome.setTaskStatus(t.status);
                                }
                            }
                        }
                    }
                    outcomes.add(outcome);
                }

                // If no activity log entries found but projects exist, add project/task info
                if (outcomes.isEmpty() && !projectsByRef.isEmpty()) {
                    for (ProjectEntity proj : projectsByRef.values()) {
                        EventProcessingOutcome projOutcome = new EventProcessingOutcome();
                        projOutcome.setType("project-created");
                        projOutcome.setSummary("Project: " + proj.name);
                        projOutcome.setProjectId(proj.id);
                        projOutcome.setProjectName(proj.name);
                        outcomes.add(projOutcome);

                        List<TaskEntity> tasks = tasksByProject.getOrDefault(proj.id, List.of());
                        for (TaskEntity t : tasks) {
                            EventProcessingOutcome taskOutcome = new EventProcessingOutcome();
                            taskOutcome.setType("task-created");
                            taskOutcome.setSummary("Task: " + t.actionType);
                            taskOutcome.setProjectId(proj.id);
                            taskOutcome.setProjectName(proj.name);
                            taskOutcome.setTaskId(t.id);
                            taskOutcome.setTaskStatus(t.status);
                            outcomes.add(taskOutcome);
                        }
                    }
                }

                if (!outcomes.isEmpty()) {
                    entry.setOutcomes(outcomes);
                }
            }

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
