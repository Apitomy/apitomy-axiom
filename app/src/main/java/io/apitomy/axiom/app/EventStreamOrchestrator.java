package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.axiom.core.events.model.RoutingRule;
import io.apitomy.axiom.core.filters.SubscriptionFilterEvaluator;
import io.apitomy.axiom.core.lifecycle.ProjectStatus;
import io.apitomy.axiom.core.services.WorkspaceService;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.manager.ManagerDecision;
import io.apitomy.axiom.manager.ManagerEvaluationResult;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Inject
    io.apitomy.axiom.core.tracing.TraceService traceService;

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
            // Filter by processEventsFrom against the event's timestamp (when it
            // occurred in the source system), not createdOn (when Axiom ingested it).
            List<StreamEventEntity> unprocessed = QuarkusTransaction.requiringNew().call(() ->
                StreamEventEntity.<StreamEventEntity>find(
                    "timestamp >= ?1 AND id NOT IN (SELECT l.eventId FROM EventProcessingLedgerEntity l " +
                    "WHERE l.subscriptionId = ?2) ORDER BY timestamp ASC",
                    sub.processEventsFrom, sub.id)
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
                routeEvent(event, sub, eventMap, ledgerId);
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
                routeEvent(event, sub, eventMap, entry.id);
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
                             Map<String, Object> eventMap, Long ledgerId) {
        if (sub.routing == null || sub.routing.isEmpty()) {
            LOG.debugf("Event %s matched subscription '%s' but no routing rules configured",
                    event.id, sub.name);
            return;
        }

        for (RoutingRule rule : sub.routing) {
            try {
                RoutingOutcomeEntity outcome = switch (rule.type()) {
                    case RoutingRule.TYPE_MANAGER -> routeToManager(event);
                    case RoutingRule.TYPE_WORKFLOW_DISPATCH -> routeToWorkflowDispatch(event, eventMap);
                    case RoutingRule.TYPE_CREATE_WORKFLOW -> routeToCreateWorkflow(event, rule);
                    case RoutingRule.TYPE_INVOKE_ACTION -> routeToInvokeAction(event, rule);
                    default -> {
                        LOG.warnf("Unknown routing type '%s' in subscription %d",
                                rule.type(), sub.id);
                        yield null;
                    }
                };
                if (outcome != null) {
                    outcome.ledgerId = ledgerId;
                    outcome.routingType = rule.type();
                    outcome.createdOn = Instant.now();
                    QuarkusTransaction.requiringNew().run(() -> outcome.persist());
                }
            } catch (Exception e) {
                // Record failed outcome (routing types may supply a pre-filled one, e.g.
                // with the trace ID of the failed Manager evaluation)
                QuarkusTransaction.requiringNew().run(() -> {
                    RoutingOutcomeEntity failedOutcome = e instanceof RoutingFailedException rfe
                            ? rfe.outcome() : new RoutingOutcomeEntity();
                    failedOutcome.ledgerId = ledgerId;
                    failedOutcome.routingType = rule.type();
                    failedOutcome.status = "failed";
                    if (failedOutcome.errorMessage == null) {
                        failedOutcome.errorMessage = e.getMessage() != null
                                ? truncate(e.getMessage(), 2000) : "Unknown error";
                    }
                    failedOutcome.createdOn = Instant.now();
                    failedOutcome.persist();
                });
                // Still throw to mark the ledger entry as failed
                throw e;
            }
        }
    }

    private RoutingOutcomeEntity routeToManager(StreamEventEntity event) {
        // Trace structure: event-ingested (root) → manager-evaluation → manager-decision
        // (one per decision) → task (for create_task / script_action decisions)
        TraceContext traceCtx = null;
        try {
            traceCtx = traceService.createTrace(
                    "manager",
                    "Manager evaluation: " + event.type + " — " + event.ref,
                    event.id, null, null,
                    "event-ingested", "Event: " + event.type + " — " + event.ref,
                    null, null);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to create trace for manager evaluation of event %s", event.id);
        }
        Long evalNodeId = addTraceNode(traceCtx, "manager-evaluation",
                "Manager evaluation: " + event.type);

        RoutingOutcomeEntity outcome = new RoutingOutcomeEntity();
        outcome.status = "completed";
        if (traceCtx != null) {
            outcome.traceId = traceCtx.traceId();
        }

        ManagerEvaluationResult evaluation;
        try {
            evaluation = managerService.evaluateStreamEvent(event,
                    traceCtx != null ? traceCtx.traceId() : null);
        } catch (RuntimeException e) {
            throw failManagerRouting(outcome, traceCtx, evalNodeId, null,
                    "Manager evaluation error: " + e.getMessage(), e);
        }
        if (evaluation == null || evaluation.failed()) {
            String error = evaluation != null && evaluation.errorMessage() != null
                    ? evaluation.errorMessage() : "Manager evaluation failed";
            throw failManagerRouting(outcome, traceCtx, evalNodeId,
                    evaluation != null ? evaluation.activityLogId() : null, error, null);
        }
        completeTraceNode(evalNodeId, "completed", evaluation.activityLogId());

        List<ManagerDecision> decisions = evaluation.decisions();
        if (decisions.isEmpty()) {
            LOG.debugf("Manager returned no decisions for stream event %s", event.id);
            outcome.summary = "No decisions";
            completeTrace(traceCtx, "completed");
            return outcome;
        }

        // Decision nodes are children of the evaluation node; task nodes are children of
        // the decision that created them.
        if (traceCtx != null && evalNodeId != null) {
            traceCtx.push(evalNodeId);
        }
        Set<Long> projectIds = new LinkedHashSet<>();
        StringBuilder summaryBuilder = new StringBuilder();
        for (ManagerDecision decision : decisions) {
            String label = decisionLabel(decision);
            if (summaryBuilder.length() > 0) summaryBuilder.append("; ");
            summaryBuilder.append(label);

            Long decisionNodeId = addTraceNode(traceCtx, "manager-decision",
                    decisionNodeSummary(decision));
            if (traceCtx != null && decisionNodeId != null) {
                traceCtx.push(decisionNodeId);
            }
            try {
                ManagerDecisionResult result = processManagerDecision(event, decision, traceCtx);
                completeTraceNode(decisionNodeId, "completed",
                        result != null ? result.activityLogId() : null);
                if (result != null) {
                    if (result.projectId() != null) {
                        projectIds.add(result.projectId());
                    }
                    if (outcome.projectId == null && result.projectId() != null) {
                        outcome.projectId = result.projectId();
                    }
                    if (outcome.taskId == null && result.taskId() != null) {
                        outcome.taskId = result.taskId();
                    }
                }
            } catch (Exception e) {
                String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                LOG.warnf(e, "Failed to process Manager decision '%s' for event %s",
                        decision.decision(), event.id);
                failDecisionNode(traceCtx, decisionNodeId, error);
                summaryBuilder.append(" failed: ").append(error);
            } finally {
                if (traceCtx != null && decisionNodeId != null) {
                    traceCtx.pop();
                }
            }
        }
        if (traceCtx != null && evalNodeId != null) {
            traceCtx.pop();
        }
        outcome.summary = truncate(summaryBuilder.toString(), 2000);

        if (traceCtx != null && projectIds.size() == 1) {
            try {
                traceService.setProjectId(traceCtx.traceId(), projectIds.iterator().next());
            } catch (Exception e) {
                LOG.warnf(e, "Failed to set project on trace %s", traceCtx.traceId());
            }
        }

        // Tasks created by the Manager share this trace; TaskTraceFinalizer completes it
        // when the last of them finishes. Only complete it here if no task is open.
        completeTraceIfNoOpenTasks(traceCtx);
        return outcome;
    }

    /**
     * Records a failed Manager evaluation: fails the evaluation node and the trace, marks
     * the outcome failed, and returns an exception that makes {@link #routeEvent} persist
     * this outcome and fail the ledger entry (so it is retried).
     */
    private RoutingFailedException failManagerRouting(RoutingOutcomeEntity outcome,
                                                      TraceContext traceCtx, Long evalNodeId,
                                                      Long activityLogId, String error,
                                                      Throwable cause) {
        LOG.warnf("Manager evaluation failed: %s", error);
        if (evalNodeId != null) {
            completeTraceNode(evalNodeId, "failed", activityLogId);
            try {
                traceService.failNode(evalNodeId, error);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to record error on trace node %d", evalNodeId);
            }
        }
        completeTrace(traceCtx, "failed");
        outcome.status = "failed";
        outcome.errorMessage = truncate(error, 2000);
        outcome.summary = "Manager evaluation failed";
        return new RoutingFailedException(error, outcome, cause);
    }

    /**
     * Fails a decision node with the error, and fails any task node under it that was left
     * open because the decision's transaction rolled back.
     */
    private void failDecisionNode(TraceContext traceCtx, Long decisionNodeId, String error) {
        if (traceCtx == null || decisionNodeId == null) return;
        try {
            traceService.failNode(decisionNodeId, error);
            List<Long> orphanTaskNodes = QuarkusTransaction.requiringNew().call(() ->
                    io.apitomy.axiom.core.entities.TraceNodeEntity
                            .<io.apitomy.axiom.core.entities.TraceNodeEntity>list(
                                    "parentNodeId = ?1 and nodeType = 'task' and status = 'in-progress'",
                                    decisionNodeId)
                            .stream().map(n -> n.id).toList());
            orphanTaskNodes.forEach(id -> traceService.completeNode(id, "failed"));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to record decision failure on trace node %d", decisionNodeId);
        }
    }

    private Long addTraceNode(TraceContext traceCtx, String nodeType, String summary) {
        if (traceCtx == null) return null;
        try {
            return traceService.addNode(traceCtx, nodeType, "in-progress", summary, null, null);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to add %s trace node to trace %s", nodeType, traceCtx.traceId());
            return null;
        }
    }

    private void completeTraceNode(Long nodeId, String status, Long activityLogId) {
        if (nodeId == null) return;
        try {
            if (activityLogId != null) {
                traceService.completeNode(nodeId, status, "activity-log", activityLogId);
            } else {
                traceService.completeNode(nodeId, status);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to complete trace node %d", nodeId);
        }
    }

    private static String decisionLabel(ManagerDecision decision) {
        return decision.actionType() != null
                ? decision.decision() + "(" + decision.actionType() + ")"
                : decision.decision();
    }

    private String decisionNodeSummary(ManagerDecision decision) {
        String prefix = managerService.meetsConfidenceThreshold(decision)
                ? "Decision: " : "Escalated (low confidence): ";
        return prefix + decisionLabel(decision)
                + String.format(" [confidence %.0f%%]", decision.confidence() * 100)
                + " — " + decision.reasoning();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    /**
     * Completes the trace unless it still has in-progress task nodes (which will
     * complete it when they finish) or has already been completed.
     */
    private void completeTraceIfNoOpenTasks(TraceContext traceCtx) {
        if (traceCtx == null) return;
        try {
            boolean hasOpenTasks = QuarkusTransaction.requiringNew().call(() ->
                    io.apitomy.axiom.core.entities.TraceNodeEntity.count(
                            "traceId = ?1 and nodeType = 'task' and status = 'in-progress'",
                            traceCtx.traceId()) > 0);
            if (hasOpenTasks) {
                return;
            }
            String status = QuarkusTransaction.requiringNew().call(() -> {
                io.apitomy.axiom.core.entities.TraceEntity trace =
                        io.apitomy.axiom.core.entities.TraceEntity.findById(traceCtx.traceId());
                return trace != null ? trace.status : null;
            });
            if ("in-progress".equals(status)) {
                completeTrace(traceCtx, "completed");
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to complete trace %s", traceCtx.traceId());
        }
    }

    private void completeTrace(TraceContext traceCtx, String status) {
        if (traceCtx == null) return;
        try {
            traceService.completeTrace(traceCtx.traceId(), status);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to complete trace %s", traceCtx.traceId());
        }
    }

    /**
     * Result of processing a single manager decision, capturing the IDs of any
     * project/task involved and the activity row written, so they can be recorded
     * in the routing outcome and on the decision's trace node.
     */
    record ManagerDecisionResult(Long projectId, Long taskId, Long activityLogId) {}

    /**
     * Thrown by a routing type to fail routing while supplying the outcome to persist.
     */
    static final class RoutingFailedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient RoutingOutcomeEntity outcome;

        RoutingFailedException(String message, RoutingOutcomeEntity outcome, Throwable cause) {
            super(message, cause);
            this.outcome = outcome;
        }

        RoutingOutcomeEntity outcome() {
            return outcome;
        }
    }

    private ManagerDecisionResult processManagerDecision(StreamEventEntity event,
                                                          ManagerDecision decision,
                                                          TraceContext traceCtx) {
        // Check confidence threshold — escalate if below
        if (!managerService.meetsConfidenceThreshold(decision)) {
            LOG.infof("Decision below confidence threshold (%.2f): %s — escalating",
                    decision.confidence(), decision.decision());
            return QuarkusTransaction.requiringNew().call(() ->
                handleEscalation(event, decision, traceCtx,
                    "Low confidence (" + String.format("%.0f%%", decision.confidence() * 100)
                        + "): " + decision.reasoning()));
        }

        return switch (decision.decision()) {
            case "create_task", "script_action" -> QuarkusTransaction.requiringNew().call(() ->
                    handleCreateTask(event, decision, traceCtx));
            case "ignore" -> QuarkusTransaction.requiringNew().call(() ->
                    handleIgnore(event, decision, traceCtx));
            case "escalate" -> QuarkusTransaction.requiringNew().call(() ->
                    handleEscalation(event, decision, traceCtx, decision.reasoning()));
            default -> throw new IllegalArgumentException(
                    "Unknown Manager decision type: " + decision.decision());
        };
    }

    private ManagerDecisionResult handleCreateTask(StreamEventEntity event,
                                                     ManagerDecision decision,
                                                     TraceContext traceCtx) {
        ProjectEntity project = findOrCreateProjectForStreamEvent(event,
                traceCtx != null ? traceCtx.traceId() : null);

        TaskEntity task = new TaskEntity();
        task.projectId = project.id;
        task.actionType = decision.actionType();
        task.createdBy = "manager";
        task.status = "Pending";
        task.input = decision.inputContext();
        task.humanContext = decision.humanContext();
        task.outputSchema = decision.outputSchema();
        task.eventId = event.id;
        task.createdOn = Instant.now();
        if (traceCtx != null) {
            task.traceId = traceCtx.traceId();
        }
        task.persist();

        // Add a trace node for this task so the task execution is linked to the trace
        if (traceCtx != null) {
            try {
                traceService.addNode(traceCtx, "task", "in-progress",
                        "Task: " + task.actionType, "task", task.id);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to add task trace node for task %d", task.id);
            }
        }

        LOG.infof("Manager created task %d (%s) for project %d from stream event %s",
                task.id, task.actionType, project.id, event.id);

        logActivity(project.id, task.id, event.id, "task-created",
                "Manager created task: " + task.actionType + " — " + decision.reasoning(),
                task.traceId);
        addThreadEntry(project.id, "manager", "decision",
                "Created task: " + task.actionType + "\n\nReasoning: " + decision.reasoning());

        sseEvents.fire(SseEvent.taskUpdated(project.id, task.id, "Pending"));
        sseEvents.fire(SseEvent.projectUpdated(project.id));
        sseEvents.fire(SseEvent.threadEntry(project.id));

        // If the action type is script-based, execute the script immediately
        // rather than waiting for the task queue to pick it up
        ActionTypeEntity actionType = ActionTypeEntity.find("name", decision.actionType())
                .firstResult();
        if (actionType != null && "script".equals(actionType.executionMode)) {
            scriptExecutionService.executeScript(task, project);
        }

        return new ManagerDecisionResult(project.id, task.id, null);
    }

    private ManagerDecisionResult handleIgnore(StreamEventEntity event, ManagerDecision decision,
                                               TraceContext traceCtx) {
        LOG.infof("Manager ignored stream event %s: %s", event.id, decision.reasoning());
        Long logId = logActivity(null, null, event.id, "event-ignored",
                "Event ignored: " + event.type + " — " + decision.reasoning(),
                traceCtx != null ? traceCtx.traceId() : null);
        return new ManagerDecisionResult(null, null, logId);
    }

    private ManagerDecisionResult handleEscalation(StreamEventEntity event,
                                                   ManagerDecision decision,
                                                   TraceContext traceCtx,
                                                   String reason) {
        LOG.infof("Manager escalated stream event %s: %s", event.id, reason);
        Long logId = logActivity(null, null, event.id, "manager-escalation",
                "Manager escalation: " + reason,
                traceCtx != null ? traceCtx.traceId() : null);

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
        return new ManagerDecisionResult(project != null ? project.id : null, null, logId);
    }

    private RoutingOutcomeEntity routeToWorkflowDispatch(StreamEventEntity event,
                                                          Map<String, Object> eventMap) {
        workflowEventDispatcher.dispatchStreamEvent(event.type, eventMap);

        RoutingOutcomeEntity outcome = new RoutingOutcomeEntity();
        outcome.status = "completed";
        outcome.summary = "Dispatched to workflow receive-event nodes";
        return outcome;
    }

    private RoutingOutcomeEntity routeToCreateWorkflow(StreamEventEntity event, RoutingRule rule) {
        if (rule.workflowDefinitionId() == null) {
            throw new IllegalStateException(
                    "create-workflow routing rule missing workflowDefinitionId");
        }
        Long projectId = findOrCreateProjectForEvent(event, null);

        // Build the event as a JsonNode so the workflow context can access
        // event fields via EL expressions (e.g., context.event.type,
        // context.event.payload.issue.title). The flow engine's
        // JsonNodeELResolver handles JsonNode navigation in EL.
        Map<String, Object> extraContext = new HashMap<>();
        try {
            com.fasterxml.jackson.databind.node.ObjectNode eventNode = objectMapper.createObjectNode();
            eventNode.put("type", event.type);
            eventNode.put("source", event.source);
            eventNode.put("connectionId", event.connectionId);
            eventNode.put("ref", event.ref);
            eventNode.put("timestamp", event.timestamp.toString());
            if (event.actor != null) {
                eventNode.set("actor", objectMapper.readTree(event.actor));
            }
            if (event.payload != null) {
                eventNode.set("payload", objectMapper.readTree(event.payload));
            }
            extraContext.put("event", eventNode);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to build event context for workflow creation");
        }

        QuarkusTransaction.requiringNew().run(() ->
                workflowExecutionService.triggerWorkflow(projectId, rule.workflowDefinitionId(), extraContext));
        LOG.infof("Created workflow (definition %d) for event %s on project %d",
                rule.workflowDefinitionId(), event.id, projectId);

        sseEvents.fire(SseEvent.projectUpdated(projectId));

        RoutingOutcomeEntity outcome = new RoutingOutcomeEntity();
        outcome.status = "completed";
        outcome.summary = "Created workflow from definition " + rule.workflowDefinitionId();
        outcome.projectId = projectId;
        return outcome;
    }

    private RoutingOutcomeEntity routeToInvokeAction(StreamEventEntity event, RoutingRule rule) {
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

        // Create a trace for this invoke-action routing
        TraceContext traceCtx = null;
        try {
            traceCtx = traceService.createTrace(
                    "invoke-action",
                    "Invoke action: " + actionType.name + " — " + event.ref,
                    event.id, null, null,
                    "invoke-action", "Invoke action: " + actionType.name,
                    null, null);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to create trace for invoke-action of event %s", event.id);
        }

        final io.apitomy.axiom.core.tracing.TraceContext finalTraceCtx = traceCtx;
        Long projectId;
        Long taskId;
        try {
            projectId = findOrCreateProjectForEvent(event,
                    traceCtx != null ? traceCtx.traceId() : null);

            // Build a structured input with a human-readable summary for {{managerInput}}
            // and the raw event payload for {{event}}
            String taskInput = buildInvokeActionInput(event);
            taskId = createInvokeActionTask(event, actionType, projectId, taskInput,
                    finalTraceCtx);
        } catch (RuntimeException e) {
            // The task was never created, so nothing else will close this trace.
            completeTrace(traceCtx, "failed");
            throw e;
        }

        // The trace is intentionally left in-progress: TaskExecutionService completes it
        // (and the task node) when the task reaches a final state.
        sseEvents.fire(SseEvent.taskUpdated(projectId, taskId, "Pending"));
        sseEvents.fire(SseEvent.projectUpdated(projectId));

        RoutingOutcomeEntity outcome = new RoutingOutcomeEntity();
        outcome.status = "completed";
        outcome.summary = "Invoked action: " + actionType.name;
        outcome.projectId = projectId;
        outcome.taskId = taskId;
        if (traceCtx != null) {
            outcome.traceId = traceCtx.traceId();
        }
        return outcome;
    }

    private Long createInvokeActionTask(StreamEventEntity event, ActionTypeEntity actionType,
            Long projectId, String taskInput,
            io.apitomy.axiom.core.tracing.TraceContext finalTraceCtx) {
        return QuarkusTransaction.requiringNew().call(() -> {
            TaskEntity task = new TaskEntity();
            task.projectId = projectId;
            task.actionType = actionType.name;
            task.createdBy = "subscription";
            task.status = "Pending";
            task.input = taskInput;
            task.eventId = event.id;
            task.createdOn = Instant.now();
            if (finalTraceCtx != null) {
                task.traceId = finalTraceCtx.traceId();
            }
            task.persist();

            // Add a trace node for the task
            if (finalTraceCtx != null) {
                try {
                    traceService.addNode(finalTraceCtx, "task", "in-progress",
                            "Task: " + task.actionType, "task", task.id);
                } catch (Exception e) {
                    LOG.warnf(e, "Failed to add task trace node for task %d", task.id);
                }
            }

            // Log activity
            logActivity(projectId, task.id, event.id, "task-created",
                    "Subscription invoked action: " + task.actionType, task.traceId);

            LOG.infof("Created task for action '%s' from event %s",
                    actionType.name, event.id);
            return task.id;
        });
    }

    /**
     * Builds the task input for invoke-action routing. Contains a JSON object with:
     * - "summary": a human-readable description of the event for {{managerInput}}
     * - "event": the raw event payload for {{event}}
     */
    private String buildInvokeActionInput(StreamEventEntity event) {
        try {
            var node = objectMapper.createObjectNode();

            // Build human-readable summary
            StringBuilder summary = new StringBuilder();
            summary.append("Event: ").append(event.type).append("\n");
            summary.append("Source: ").append(event.source).append("\n");
            summary.append("Ref: ").append(event.ref).append("\n");

            // Extract key details from payload
            try {
                JsonNode payload = objectMapper.readTree(event.payload);
                JsonNode issue = payload.path("issue");
                JsonNode pr = payload.path("pullRequest");
                if (!issue.isMissingNode()) {
                    summary.append("Title: ").append(issue.path("title").asText("")).append("\n");
                    String body = issue.path("body").asText(null);
                    if (body != null && !body.isBlank()) {
                        summary.append("Body: ").append(body.length() > 500
                                ? body.substring(0, 500) + "..." : body).append("\n");
                    }
                    summary.append("State: ").append(issue.path("state").asText("")).append("\n");
                } else if (!pr.isMissingNode()) {
                    summary.append("Title: ").append(pr.path("title").asText("")).append("\n");
                    String body = pr.path("body").asText(null);
                    if (body != null && !body.isBlank()) {
                        summary.append("Body: ").append(body.length() > 500
                                ? body.substring(0, 500) + "..." : body).append("\n");
                    }
                    summary.append("Base branch: ").append(pr.path("baseBranch").asText("")).append("\n");
                }
            } catch (Exception e) {
                LOG.debugf("Failed to extract details from event payload: %s", e.getMessage());
            }

            node.put("summary", summary.toString());
            node.set("event", objectMapper.readTree(event.payload));

            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to build invoke-action input for event %s", event.id);
            return event.payload;
        }
    }

    private Long findOrCreateProjectForEvent(StreamEventEntity event, UUID traceId) {
        return QuarkusTransaction.requiringNew().call(() ->
                findOrCreateProjectForStreamEvent(event, traceId).id);
    }

    private ProjectEntity findOrCreateProjectForStreamEvent(StreamEventEntity event,
                                                            UUID traceId) {
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

        logActivity(project.id, null, event.id, "project-created",
                "Project auto-created from " + event.type + " event", traceId);
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
                    return new SubscriptionWithFilters(e.id, e.name, e.labels, e.filters, rules,
                            e.processEventsFrom != null ? e.processEventsFrom : Instant.EPOCH);
                })
                .toList();
    }

    record SubscriptionWithFilters(long id, String name, List<String> labels,
                                    String filterExpression, List<RoutingRule> routing,
                                    Instant processEventsFrom) {}

    // ── Activity and thread logging ────────────────────────────────

    private Long logActivity(Long projectId, Long taskId, UUID eventId,
                             String entryType, String summary, UUID traceId) {
        ActivityLogEntity log = new ActivityLogEntity();
        log.projectId = projectId;
        log.taskId = taskId;
        log.eventId = eventId;
        log.entryType = entryType;
        log.summary = summary != null && summary.length() > 1024
                ? summary.substring(0, 1021) + "..."
                : summary;
        log.createdOn = Instant.now();
        log.traceId = traceId;
        log.persist();
        return log.id;
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
