package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.events.model.RoutingRule;
import io.apitomy.axiom.core.filters.SubscriptionFilterEvaluator;
import io.apitomy.axiom.manager.ManagerDecision;
import io.apitomy.axiom.manager.ManagerService;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Polls the {@code stream_event} table for new events, evaluates them against
 * all enabled {@link EventSubscriptionEntity} rows using
 * {@link SubscriptionFilterEvaluator}, and routes matching events to the
 * workflow dispatcher (and, in a future phase, the Manager for triage).
 *
 * <p>Uses an in-memory cursor ({@link #lastProcessedTimestamp}) initialized
 * from the most recent {@code stream_event.createdOn} on first poll, so
 * historical events are not reprocessed on startup.</p>
 */
@ApplicationScoped
public class EventStreamOrchestrator {

    private static final Logger LOG = Logger.getLogger(EventStreamOrchestrator.class);

    @Inject
    SubscriptionFilterEvaluator filterEvaluator;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WorkflowEventDispatcher workflowEventDispatcher;

    @Inject
    ManagerService managerService;

    @Inject
    WorkflowExecutionService workflowExecutionService;

    private volatile boolean shuttingDown = false;

    // Cursor: timestamp of the last processed event
    private volatile Instant lastProcessedTimestamp = null;
    private volatile boolean initialized = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    @Scheduled(every = "${axiom.stream-pipeline.poll-interval:5s}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void processNewEvents() {
        if (shuttingDown) return;

        // Initialize cursor on first run
        if (!initialized) {
            QuarkusTransaction.requiringNew().run(this::initializeCursor);
            initialized = true;
            return;  // Skip processing on first tick, start fresh next tick
        }

        // Find new events since last processed
        List<StreamEventEntity> newEvents = QuarkusTransaction.requiringNew().call(() -> {
            if (lastProcessedTimestamp == null) {
                return StreamEventEntity.<StreamEventEntity>find(
                        "ORDER BY createdOn ASC")
                        .page(0, 100).list();
            } else {
                return StreamEventEntity.<StreamEventEntity>find(
                        "createdOn > ?1 ORDER BY createdOn ASC", lastProcessedTimestamp)
                        .page(0, 100).list();
            }
        });

        if (newEvents.isEmpty()) return;

        // Load all enabled subscriptions once per batch
        List<SubscriptionWithFilters> subscriptions = QuarkusTransaction.requiringNew()
                .call(this::loadSubscriptions);

        for (StreamEventEntity event : newEvents) {
            if (shuttingDown) break;
            try {
                processEvent(event, subscriptions);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to process stream event %s", event.id);
            }
            lastProcessedTimestamp = event.createdOn;
        }
    }

    private void initializeCursor() {
        // Set cursor to the most recent event's createdOn, so we don't
        // reprocess historical events on startup
        StreamEventEntity latest = StreamEventEntity.<StreamEventEntity>find(
                "ORDER BY createdOn DESC").firstResult();
        if (latest != null) {
            lastProcessedTimestamp = latest.createdOn;
            LOG.infof("Event stream cursor initialized to %s", lastProcessedTimestamp);
        } else {
            LOG.info("Event stream is empty, cursor starts from the beginning");
        }
    }

    private void processEvent(StreamEventEntity event,
                               List<SubscriptionWithFilters> subscriptions) {
        // Parse payload for filter evaluation
        JsonNode payloadNode = null;
        try {
            payloadNode = objectMapper.readTree(event.payload);
        } catch (Exception e) {
            LOG.warnf("Failed to parse payload for event %s: %s", event.id, e.getMessage());
        }

        // Build event map once for all subscription filter evaluations
        Map<String, Object> eventMap = buildEventMap(event, payloadNode);

        for (SubscriptionWithFilters sub : subscriptions) {
            if (filterEvaluator.matches(sub.filterExpression, eventMap)) {
                routeEvent(event, sub, eventMap);
            }
        }
    }

    private void routeEvent(StreamEventEntity event, SubscriptionWithFilters sub,
                             Map<String, Object> eventMap) {
        if (sub.routing == null || sub.routing.isEmpty()) {
            LOG.debugf("Event %s matched subscription '%s' but no routing rules configured",
                    event.id, sub.name);
            return;
        }

        for (RoutingRule rule : sub.routing) {
            try {
                switch (rule.type()) {
                    case RoutingRule.TYPE_MANAGER -> routeToManager(event);
                    case RoutingRule.TYPE_WORKFLOW_DISPATCH -> routeToWorkflowDispatch(event, eventMap);
                    case RoutingRule.TYPE_CREATE_WORKFLOW -> routeToCreateWorkflow(event, rule, eventMap);
                    case RoutingRule.TYPE_INVOKE_ACTION -> routeToInvokeAction(event, rule, eventMap);
                    default -> LOG.warnf("Unknown routing type '%s' in subscription %d",
                            rule.type(), sub.id);
                }
            } catch (Exception e2) {
                LOG.warnf(e2, "Failed to route event %s via '%s' for subscription '%s'",
                        event.id, rule.type(), sub.name);
            }
        }

        LOG.debugf("Event %s matched subscription '%s' (id=%d)",
                event.id, sub.name, sub.id);
    }

    private void routeToManager(StreamEventEntity event) {
        List<ManagerDecision> decisions = managerService.evaluateStreamEvent(event);
        if (decisions != null && !decisions.isEmpty()) {
            LOG.infof("Manager returned %d decisions for stream event %s",
                    decisions.size(), event.id);
        }
    }

    private void routeToWorkflowDispatch(StreamEventEntity event, Map<String, Object> eventMap) {
        workflowEventDispatcher.dispatchStreamEvent(event.type, eventMap);
    }

    private void routeToCreateWorkflow(StreamEventEntity event, RoutingRule rule,
                                        Map<String, Object> eventMap) {
        if (rule.workflowDefinitionId() == null) {
            LOG.warnf("create-workflow routing rule missing workflowDefinitionId for event %s",
                    event.id);
            return;
        }

        Long projectId = findOrCreateProjectForEvent(event);
        if (projectId == null) {
            LOG.warnf("Could not find or create project for event %s (ref: %s)",
                    event.id, event.ref);
            return;
        }

        try {
            QuarkusTransaction.requiringNew().run(() -> {
                workflowExecutionService.triggerWorkflow(projectId, rule.workflowDefinitionId());
            });
            LOG.infof("Created workflow (definition %d) for event %s on project %d",
                    rule.workflowDefinitionId(), event.id, projectId);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to create workflow for event %s", event.id);
        }
    }

    private void routeToInvokeAction(StreamEventEntity event, RoutingRule rule,
                                      Map<String, Object> eventMap) {
        if (rule.actionTypeId() == null) {
            LOG.warnf("invoke-action routing rule missing actionTypeId for event %s", event.id);
            return;
        }

        ActionTypeEntity actionType = QuarkusTransaction.requiringNew().call(() ->
                ActionTypeEntity.findById(rule.actionTypeId()));
        if (actionType == null) {
            LOG.warnf("Action type %d not found for invoke-action routing", rule.actionTypeId());
            return;
        }

        Long projectId = findOrCreateProjectForEvent(event);
        if (projectId == null) {
            LOG.warnf("Could not find or create project for event %s", event.id);
            return;
        }

        QuarkusTransaction.requiringNew().run(() -> {
            TaskEntity task = new TaskEntity();
            task.projectId = projectId;
            task.actionType = actionType.name;
            task.createdBy = "subscription";
            task.status = "Pending";
            task.input = event.payload;
            task.createdOn = Instant.now();
            task.persist();
            LOG.infof("Created task for action '%s' from event %s", actionType.name, event.id);
        });
    }

    private Long findOrCreateProjectForEvent(StreamEventEntity event) {
        return QuarkusTransaction.requiringNew().call(() -> {
            // Try to find existing project by ref
            ProjectEntity project = ProjectEntity.find("ref", event.ref).firstResult();
            if (project != null) return project.id;

            // Auto-create a project
            project = new ProjectEntity();
            project.name = "Event: " + event.type + " — " + event.ref;
            project.ref = event.ref;
            project.type = "event";
            project.status = "Active";
            project.createdOn = Instant.now();
            project.updatedOn = Instant.now();
            project.persist();
            return project.id;
        });
    }

    /**
     * Builds the event map used for workflow receive-event node matching.
     * Compatible with the Flow engine's EL evaluation.
     */
    Map<String, Object> buildEventMap(StreamEventEntity event, JsonNode payloadNode) {
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

        // Parse payload into a map for EL access
        if (payloadNode != null) {
            try {
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

    private List<SubscriptionWithFilters> loadSubscriptions() {
        List<EventSubscriptionEntity> entities = EventSubscriptionEntity.list("enabled", true);
        return entities.stream()
                .map(e -> {
                    List<RoutingRule> rules = List.of();
                    if (e.routing != null && !e.routing.isBlank()) {
                        try {
                            rules = objectMapper.readValue(e.routing,
                                    objectMapper.getTypeFactory().constructCollectionType(
                                            List.class, RoutingRule.class));
                        } catch (Exception ex) {
                            LOG.warnf("Failed to parse routing for subscription %d", e.id);
                        }
                    }
                    return new SubscriptionWithFilters(e.id, e.name, e.labels, e.filters, rules);
                })
                .toList();
    }

    record SubscriptionWithFilters(long id, String name, List<String> labels,
                                    String filterExpression, List<RoutingRule> routing) {}
}
