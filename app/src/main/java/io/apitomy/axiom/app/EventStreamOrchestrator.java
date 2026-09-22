package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.axiom.core.events.model.RoutingRule;
import io.apitomy.axiom.core.filters.SubscriptionFilterEvaluator;
import io.apitomy.axiom.core.lifecycle.ProjectStatus;
import io.apitomy.axiom.core.services.WorkspaceService;
import io.apitomy.axiom.manager.ManagerDecision;
import io.apitomy.axiom.manager.ManagerService;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
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

    @Inject
    ScriptExecutionService scriptExecutionService;

    @Inject
    Event<SseEvent> sseEvents;

    @Inject
    WorkspaceService workspaceService;

    private volatile boolean shuttingDown = false;
    private volatile boolean startupRecoveryDone = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    @Scheduled(every = "${axiom.stream-pipeline.poll-interval:5s}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void processNewEvents() {
        if (shuttingDown) return;

        // On first tick, recover orphaned "pending" ledger entries from a
        // previous crash by marking them as "failed" so the retry loop
        // picks them up.
        if (!startupRecoveryDone) {
            startupRecoveryDone = true;
            QuarkusTransaction.requiringNew().run(() -> {
                long recovered = EventProcessingLedgerEntity.update(
                        "status = 'failed', errorMessage = 'Recovered on startup: " +
                        "previous instance crashed before completing routing' " +
                        "where status = 'pending'");
                if (recovered > 0) {
                    LOG.infof("Recovered %d orphaned pending ledger entries on startup",
                            recovered);
                }
            });
        }

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
        if (decisions == null || decisions.isEmpty()) {
            LOG.debugf("Manager returned no decisions for stream event %s", event.id);
            return;
        }

        for (ManagerDecision decision : decisions) {
            try {
                processManagerDecision(event, decision);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to process Manager decision '%s' for event %s",
                        decision.decision(), event.id);
            }
        }
    }

    private void processManagerDecision(StreamEventEntity event, ManagerDecision decision) {
        // Check confidence threshold — escalate if below
        if (!managerService.meetsConfidenceThreshold(decision)) {
            LOG.infof("Decision below confidence threshold (%.2f): %s — escalating",
                    decision.confidence(), decision.decision());
            QuarkusTransaction.requiringNew().run(() ->
                handleEscalation(event, decision,
                    "Low confidence (" + String.format("%.0f%%", decision.confidence() * 100)
                        + "): " + decision.reasoning()));
            return;
        }

        switch (decision.decision()) {
            case "create_task" -> QuarkusTransaction.requiringNew().run(() ->
                    handleCreateTask(event, decision));
            case "ignore" -> QuarkusTransaction.requiringNew().run(() ->
                    handleIgnore(event, decision));
            case "script_action" -> QuarkusTransaction.requiringNew().run(() ->
                    handleScriptAction(event, decision));
            case "escalate" -> QuarkusTransaction.requiringNew().run(() ->
                    handleEscalation(event, decision, decision.reasoning()));
            default -> LOG.warnf("Unknown Manager decision type: %s", decision.decision());
        }
    }

    private void handleCreateTask(StreamEventEntity event, ManagerDecision decision) {
        ProjectEntity project = findOrCreateProjectForStreamEvent(event);

        TaskEntity task = new TaskEntity();
        task.projectId = project.id;
        task.actionType = decision.actionType();
        task.createdBy = "manager";
        task.status = "Pending";
        task.input = decision.inputContext();
        task.humanContext = decision.humanContext();
        task.outputSchema = decision.outputSchema();
        task.createdOn = Instant.now();
        task.persist();

        LOG.infof("Manager created task %d (%s) for project %d from stream event %s",
                task.id, task.actionType, project.id, event.id);

        logActivity(project.id, task.id, null, "task-created",
                "Manager created task: " + task.actionType + " — " + decision.reasoning());
        addThreadEntry(project.id, "manager", "decision",
                "Created task: " + task.actionType + "\n\nReasoning: " + decision.reasoning());

        sseEvents.fire(SseEvent.taskUpdated(project.id, task.id, "Pending"));
        sseEvents.fire(SseEvent.projectUpdated(project.id));
        sseEvents.fire(SseEvent.threadEntry(project.id));
    }

    private void handleIgnore(StreamEventEntity event, ManagerDecision decision) {
        LOG.infof("Manager ignored stream event %s: %s", event.id, decision.reasoning());
        logActivity(null, null, null, "event-ignored",
                "Event ignored: " + event.type + " — " + decision.reasoning());
    }

    private void handleScriptAction(StreamEventEntity event, ManagerDecision decision) {
        ProjectEntity project = findOrCreateProjectForStreamEvent(event);

        TaskEntity task = new TaskEntity();
        task.projectId = project.id;
        task.actionType = decision.actionType();
        task.createdBy = "manager";
        task.status = "Pending";
        task.input = decision.inputContext();
        task.humanContext = decision.humanContext();
        task.outputSchema = decision.outputSchema();
        task.createdOn = Instant.now();
        task.persist();

        LOG.infof("Manager created script task %d (%s) for project %d from stream event %s",
                task.id, task.actionType, project.id, event.id);

        logActivity(project.id, task.id, null, "task-created",
                "Manager created script task: " + task.actionType + " — " + decision.reasoning());
        addThreadEntry(project.id, "manager", "decision",
                "Script action: " + task.actionType + "\n\nReasoning: " + decision.reasoning());

        sseEvents.fire(SseEvent.taskUpdated(project.id, task.id, "Pending"));
        sseEvents.fire(SseEvent.projectUpdated(project.id));
        sseEvents.fire(SseEvent.threadEntry(project.id));

        // Execute the script immediately
        scriptExecutionService.executeScript(task, project);
    }

    private void handleEscalation(StreamEventEntity event, ManagerDecision decision,
                                   String reason) {
        LOG.infof("Manager escalated stream event %s: %s", event.id, reason);
        logActivity(null, null, null, "manager-escalation",
                "Manager escalation: " + reason);

        // If we can find a project for this event, add to its thread
        ProjectEntity project = QuarkusTransaction.requiringNew().call(() ->
                ProjectEntity.find("ref", event.ref).<ProjectEntity>firstResult());
        if (project != null) {
            addThreadEntry(project.id, "manager", "question",
                    "Escalation: " + reason
                            + "\n\nThe Manager needs your input on how to handle this event.");
            sseEvents.fire(SseEvent.threadEntry(project.id));
        }

        sseEvents.fire(SseEvent.notification("Manager escalation: " + reason, "warning"));
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

        sseEvents.fire(SseEvent.projectUpdated(projectId));
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

        Long taskId = QuarkusTransaction.requiringNew().call(() -> {
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
            return task.id;
        });

        sseEvents.fire(SseEvent.taskUpdated(projectId, taskId, "Pending"));
        sseEvents.fire(SseEvent.projectUpdated(projectId));
    }

    private Long findOrCreateProjectForEvent(StreamEventEntity event) {
        return QuarkusTransaction.requiringNew().call(() ->
                findOrCreateProjectForStreamEvent(event).id);
    }

    private ProjectEntity findOrCreateProjectForStreamEvent(StreamEventEntity event) {
        // Try to find existing project by ref
        ProjectEntity project = ProjectEntity.find("ref", event.ref).firstResult();
        if (project != null) return project;

        // Auto-create with metadata from the payload
        project = new ProjectEntity();
        project.ref = event.ref;
        project.refSource = event.source;
        project.type = determineProjectType(event.type);
        project.status = ProjectStatus.Created.name();
        project.createdOn = Instant.now();
        project.updatedOn = Instant.now();

        // Extract title and body from the normalized payload
        try {
            JsonNode payload = objectMapper.readTree(event.payload);
            // The normalized payload uses "issue.title" or "pullRequest.title"
            String title = payload.path("issue").path("title").asText(null);
            if (title == null) title = payload.path("pullRequest").path("title").asText(null);
            project.name = title != null ? title : event.ref;

            String body = payload.path("issue").path("body").asText(null);
            if (body == null) body = payload.path("pullRequest").path("body").asText(null);
            project.body = body;
        } catch (Exception e) {
            project.name = event.ref;
        }

        project.persist();

        LOG.infof("Auto-created project %d for %s", project.id, event.ref);

        logActivity(project.id, null, null, "project-created",
                "Project auto-created from " + event.type + " event");
        addThreadEntry(project.id, "system", "message",
                "Project created from " + event.source + " event: " + event.type);

        // Ensure the workspace directory exists
        try {
            workspaceService.ensureWorkspace(project);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to create workspace for project %d", project.id);
        }

        return project;
    }

    private String determineProjectType(String eventType) {
        if (eventType != null) {
            if (eventType.startsWith("issue.")) return "issue";
            if (eventType.startsWith("pr.")) return "pull-request";
        }
        return "other";
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

    // ── Activity and thread logging ────────────────────────────────

    private void logActivity(Long projectId, Long taskId, Long eventId,
                              String entryType, String summary) {
        ActivityLogEntity log = new ActivityLogEntity();
        log.projectId = projectId;
        log.taskId = taskId;
        log.eventId = eventId;
        log.entryType = entryType;
        log.summary = summary != null && summary.length() > 1024
                ? summary.substring(0, 1021) + "..."
                : summary;
        log.createdOn = Instant.now();
        log.persist();
    }

    private void addThreadEntry(Long projectId, String authorType, String entryType,
                                  String content) {
        ThreadEntryEntity entry = new ThreadEntryEntity();
        entry.projectId = projectId;
        entry.authorType = authorType;
        entry.entryType = entryType;
        entry.content = content;
        entry.createdOn = Instant.now();
        entry.persist();
    }
}
