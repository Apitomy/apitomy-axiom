package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
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
import java.util.UUID;

/**
 * Processes stream events against enabled subscriptions using a durable
 * processing ledger. The ledger tracks (event, subscription) pairs with
 * status (pending/completed/skipped/failed), surviving restarts and
 * enabling retry of failed routing.
 *
 * <p>On each tick:</p>
 * <ol>
 *   <li>Find stream events that have unprocessed subscriptions (no ledger entry)</li>
 *   <li>For each (event, subscription) pair without a ledger entry:
 *       evaluate the filter, create a ledger entry, route if matched</li>
 *   <li>Retry any "failed" ledger entries</li>
 * </ol>
 */
@ApplicationScoped
public class EventStreamOrchestrator {

    private static final Logger LOG = Logger.getLogger(EventStreamOrchestrator.class);
    private static final int BATCH_SIZE = 50;

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

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    @Scheduled(every = "${axiom.stream-pipeline.poll-interval:5s}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void processNewEvents() {
        if (shuttingDown) return;

        // Load enabled subscriptions
        List<SubscriptionWithFilters> subscriptions = QuarkusTransaction.requiringNew()
                .call(this::loadSubscriptions);
        if (subscriptions.isEmpty()) return;

        // Process new (event, subscription) pairs that have no ledger entry
        processUnledgeredPairs(subscriptions);

        // Retry failed entries
        if (!shuttingDown) {
            retryFailedEntries(subscriptions);
        }
    }

    /**
     * Finds stream events that have at least one enabled subscription without
     * a ledger entry, and processes those pairs.
     */
    private void processUnledgeredPairs(List<SubscriptionWithFilters> subscriptions) {
        for (SubscriptionWithFilters sub : subscriptions) {
            if (shuttingDown) break;

            // Find events that have no ledger entry for this subscription.
            // Use a NOT IN subquery for efficiency.
            List<StreamEventEntity> unprocessed = QuarkusTransaction.requiringNew().call(() ->
                StreamEventEntity.<StreamEventEntity>find(
                    "id NOT IN (SELECT l.eventId FROM EventProcessingLedgerEntity l " +
                    "WHERE l.subscriptionId = ?1) ORDER BY createdOn ASC", sub.id)
                    .page(0, BATCH_SIZE).list()
            );

            for (StreamEventEntity event : unprocessed) {
                if (shuttingDown) break;
                processEventForSubscription(event, sub);
            }
        }
    }

    /**
     * Evaluates a single event against a subscription, creates a ledger entry,
     * and routes if matched. Each step runs in its own transaction.
     */
    private void processEventForSubscription(StreamEventEntity event,
                                              SubscriptionWithFilters sub) {
        try {
            // Build event map for filter evaluation
            JsonNode payloadNode = null;
            try {
                payloadNode = objectMapper.readTree(event.payload);
            } catch (Exception e) {
                LOG.warnf("Failed to parse payload for event %s: %s", event.id, e.getMessage());
            }
            Map<String, Object> eventMap = buildEventMap(event, payloadNode);

            // Evaluate filter
            boolean matched = filterEvaluator.matches(sub.filterExpression, eventMap);

            if (!matched) {
                // Create a "skipped" ledger entry so we don't re-evaluate
                createLedgerEntry(event.id, sub.id, "skipped", null);
                return;
            }

            // Create a "pending" ledger entry
            Long ledgerId = createLedgerEntry(event.id, sub.id, "pending", null);

            // Execute routing rules
            try {
                routeEvent(event, sub, eventMap);
                completeLedgerEntry(ledgerId);
            } catch (Exception e) {
                failLedgerEntry(ledgerId, e.getMessage());
                LOG.warnf(e, "Routing failed for event %s / subscription %d", event.id, sub.id);
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to process event %s for subscription %d", event.id, sub.id);
        }
    }

    /**
     * Retries ledger entries with status "failed".
     */
    private void retryFailedEntries(List<SubscriptionWithFilters> subscriptions) {
        List<EventProcessingLedgerEntity> failedEntries = QuarkusTransaction.requiringNew().call(() ->
            EventProcessingLedgerEntity.<EventProcessingLedgerEntity>find(
                "status = ?1 ORDER BY createdOn ASC", "failed")
                .page(0, BATCH_SIZE).list()
        );

        for (EventProcessingLedgerEntity entry : failedEntries) {
            if (shuttingDown) break;

            SubscriptionWithFilters sub = subscriptions.stream()
                    .filter(s -> s.id == entry.subscriptionId)
                    .findFirst().orElse(null);
            if (sub == null) continue; // Subscription no longer enabled or deleted

            StreamEventEntity event = QuarkusTransaction.requiringNew().call(() ->
                    StreamEventEntity.findById(entry.eventId));
            if (event == null) {
                // Event was deleted (retention); clean up the ledger entry
                QuarkusTransaction.requiringNew().run(() -> {
                    EventProcessingLedgerEntity e = EventProcessingLedgerEntity.findById(entry.id);
                    if (e != null) e.delete();
                });
                continue;
            }

            try {
                JsonNode payloadNode = objectMapper.readTree(event.payload);
                Map<String, Object> eventMap = buildEventMap(event, payloadNode);
                routeEvent(event, sub, eventMap);
                completeLedgerEntry(entry.id);
                LOG.infof("Retry succeeded for event %s / subscription %d", event.id, sub.id);
            } catch (Exception e) {
                failLedgerEntry(entry.id, e.getMessage());
            }
        }
    }

    // ── Ledger entry management ─────────────────────────────────

    private Long createLedgerEntry(UUID eventId, long subscriptionId,
                                    String status, String errorMessage) {
        return QuarkusTransaction.requiringNew().call(() -> {
            // Check for existing entry (dedup)
            EventProcessingLedgerEntity existing = EventProcessingLedgerEntity.find(
                    "eventId = ?1 and subscriptionId = ?2", eventId, subscriptionId)
                    .firstResult();
            if (existing != null) return existing.id;

            EventProcessingLedgerEntity entry = new EventProcessingLedgerEntity();
            entry.eventId = eventId;
            entry.subscriptionId = subscriptionId;
            entry.status = status;
            entry.errorMessage = errorMessage;
            entry.createdOn = Instant.now();
            if ("completed".equals(status) || "skipped".equals(status)) {
                entry.processedOn = Instant.now();
            }
            entry.persist();
            return entry.id;
        });
    }

    private void completeLedgerEntry(Long ledgerId) {
        QuarkusTransaction.requiringNew().run(() -> {
            EventProcessingLedgerEntity entry = EventProcessingLedgerEntity.findById(ledgerId);
            if (entry != null) {
                entry.status = "completed";
                entry.errorMessage = null;
                entry.processedOn = Instant.now();
            }
        });
    }

    private void failLedgerEntry(Long ledgerId, String errorMessage) {
        QuarkusTransaction.requiringNew().run(() -> {
            EventProcessingLedgerEntity entry = EventProcessingLedgerEntity.findById(ledgerId);
            if (entry != null) {
                entry.status = "failed";
                entry.errorMessage = errorMessage != null
                        ? errorMessage.substring(0, Math.min(errorMessage.length(), 2000))
                        : null;
                entry.processedOn = Instant.now();
            }
        });
    }

    // ── Routing ─────────────────────────────────────────────────

    private void routeEvent(StreamEventEntity event, SubscriptionWithFilters sub,
                             Map<String, Object> eventMap) {
        if (sub.routing == null || sub.routing.isEmpty()) {
            LOG.debugf("Event %s matched subscription '%s' but no routing rules configured",
                    event.id, sub.name);
            return;
        }

        for (RoutingRule rule : sub.routing) {
            switch (rule.type()) {
                case RoutingRule.TYPE_MANAGER -> routeToManager(event);
                case RoutingRule.TYPE_WORKFLOW_DISPATCH -> routeToWorkflowDispatch(event, eventMap);
                case RoutingRule.TYPE_CREATE_WORKFLOW -> routeToCreateWorkflow(event, rule);
                case RoutingRule.TYPE_INVOKE_ACTION -> routeToInvokeAction(event, rule);
                default -> LOG.warnf("Unknown routing type '%s' in subscription %d",
                        rule.type(), sub.id);
            }
        }
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

    private void routeToCreateWorkflow(StreamEventEntity event, RoutingRule rule) {
        if (rule.workflowDefinitionId() == null) {
            throw new IllegalStateException(
                    "create-workflow routing rule missing workflowDefinitionId");
        }
        Long projectId = findOrCreateProjectForEvent(event);
        QuarkusTransaction.requiringNew().run(() ->
                workflowExecutionService.triggerWorkflow(projectId, rule.workflowDefinitionId()));
        LOG.infof("Created workflow (definition %d) for event %s on project %d",
                rule.workflowDefinitionId(), event.id, projectId);
    }

    private void routeToInvokeAction(StreamEventEntity event, RoutingRule rule) {
        if (rule.actionTypeId() == null) {
            throw new IllegalStateException(
                    "invoke-action routing rule missing actionTypeId");
        }

        ActionTypeEntity actionType = QuarkusTransaction.requiringNew().call(() ->
                ActionTypeEntity.findById(rule.actionTypeId()));
        if (actionType == null) {
            throw new IllegalStateException(
                    "Action type " + rule.actionTypeId() + " not found");
        }

        Long projectId = findOrCreateProjectForEvent(event);

        QuarkusTransaction.requiringNew().run(() -> {
            TaskEntity task = new TaskEntity();
            task.projectId = projectId;
            task.actionType = actionType.name;
            task.createdBy = "subscription";
            task.status = "Pending";
            task.input = event.payload;
            task.createdOn = Instant.now();
            task.persist();
            LOG.infof("Created task for action '%s' from event %s",
                    actionType.name, event.id);
        });
    }

    private Long findOrCreateProjectForEvent(StreamEventEntity event) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ProjectEntity project = ProjectEntity.find("ref", event.ref).firstResult();
            if (project != null) return project.id;

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

    // ── Event map building ──────────────────────────────────────

    Map<String, Object> buildEventMap(StreamEventEntity event, JsonNode payloadNode) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", event.type);
        map.put("source", event.source);
        map.put("connectionId", event.connectionId);
        map.put("ref", event.ref);
        map.put("timestamp", event.timestamp.toString());

        if (event.actor != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> actor = objectMapper.readValue(event.actor, Map.class);
                map.put("actor", actor);
            } catch (Exception e) {
                map.put("actor", Map.of());
            }
        }

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

    // ── Subscription loading ────────────────────────────────────

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
