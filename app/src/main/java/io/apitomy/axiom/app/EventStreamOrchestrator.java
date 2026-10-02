package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
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
 *   <li>Retry "failed" ledger entries whose backoff delay has passed</li>
 * </ol>
 */
@ApplicationScoped
public class EventStreamOrchestrator {

    private static final Logger LOG = Logger.getLogger(EventStreamOrchestrator.class);
    private static final int BATCH_SIZE = 50;

    /**
     * Routing type of the failed outcome written when processing fails outside a routing
     * rule (it is not a rule type, so it never matches a rule when skipping on retry).
     */
    static final String ROUTING_TYPE_PROCESSING = "processing";

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

    @Inject
    ManagerTraceRecorder managerTraceRecorder;

    /**
     * Maximum routing attempts per ledger entry, the first try included. The entry is
     * {@code exhausted} when the attempt that reaches this count fails.
     */
    @ConfigProperty(name = "axiom.stream-pipeline.max-attempts", defaultValue = "3")
    int maxAttempts;

    /**
     * Delay before the first retry; each later retry doubles it.
     */
    @ConfigProperty(name = "axiom.stream-pipeline.retry-initial-delay", defaultValue = "30s")
    Duration retryInitialDelay;

    /**
     * Upper bound of the delay between two attempts.
     */
    @ConfigProperty(name = "axiom.stream-pipeline.retry-max-delay", defaultValue = "1h")
    Duration retryMaxDelay;

    private volatile boolean shuttingDown = false;
    private volatile boolean startupRecoveryDone = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    /**
     * Returns the configured maximum number of attempts per ledger entry.
     *
     * @return the value of {@code axiom.stream-pipeline.max-attempts}
     */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Delay before the retry that follows failed attempt number {@code attempt}:
     * {@code min(initialDelay * 2^(attempt - 1), maxDelay)}.
     *
     * @param attempt the 1-based number of the attempt that just failed
     * @return the delay until the next attempt
     */
    Duration backoffDelay(int attempt) {
        int doublings = Math.max(0, Math.min(attempt - 1, 30));
        Duration delay;
        try {
            delay = retryInitialDelay.multipliedBy(1L << doublings);
        } catch (ArithmeticException e) {
            return retryMaxDelay;
        }
        return delay.compareTo(retryMaxDelay) > 0 ? retryMaxDelay : delay;
    }

    @Scheduled(every = "${axiom.stream-pipeline.poll-interval:5s}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void processNewEvents() {
        if (shuttingDown) return;
        // Entries attempted during this tick (by the first pass) are never retried in it
        Instant tickStart = Instant.now();

        // On first tick, recover orphaned "pending" ledger entries from a
        // previous crash: the interrupted attempt counts, so an entry that was on its
        // last attempt is exhausted and the others are due immediately.
        if (!startupRecoveryDone) {
            startupRecoveryDone = true;
            QuarkusTransaction.requiringNew().run(() -> {
                String reason = "Recovered on startup: previous instance crashed before "
                        + "completing routing";
                long exhausted = EventProcessingLedgerEntity.update(
                        "status = 'exhausted', nextAttemptAt = null, errorMessage = ?1 "
                        + "where status = 'pending' and attemptCount >= ?2",
                        reason + " (giving up after the last attempt)", maxAttempts);
                long recovered = EventProcessingLedgerEntity.update(
                        "status = 'failed', nextAttemptAt = ?1, errorMessage = ?2 "
                        + "where status = 'pending'", Instant.now(), reason);
                if (recovered + exhausted > 0) {
                    LOG.infof("Recovered %d orphaned pending ledger entries on startup "
                            + "(%d of them on their last attempt)", recovered + exhausted,
                            exhausted);
                }
            });
        }

        // Load enabled subscriptions
        List<SubscriptionWithFilters> subscriptions = QuarkusTransaction.requiringNew()
                .call(this::loadSubscriptions);
        if (subscriptions.isEmpty()) return;

        // Process new (event, subscription) pairs that have no ledger entry
        processUnledgeredPairs(subscriptions);

        // Retry failed entries that are due
        if (!shuttingDown) {
            retryFailedEntries(subscriptions, tickStart);
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
            // Every failure creates a ledger entry, so a failing pair leaves this query
            // and is retried (with backoff and a cap) by the retry pass only.
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
     * and routes if matched. Each step runs in its own transaction. A failure before the
     * entry exists (building the event context, evaluating the filter) creates a failed
     * entry, so it is retried with backoff and counts toward the cap like any other.
     */
    private void processEventForSubscription(StreamEventEntity event,
                                              SubscriptionWithFilters sub) {
        Map<String, Object> eventMap;
        boolean matched;
        try {
            eventMap = buildEventMap(event, parsePayload(event));
            matched = filterEvaluator.matches(sub.filterExpression, eventMap);
        } catch (Exception e) {
            try {
                Long ledgerId = createLedgerEntry(event.id, sub.id, "pending");
                if (ledgerId != null) {
                    failProcessing(ledgerId, 1, e);
                }
            } catch (Exception inner) {
                LOG.errorf(inner, "Failed to record processing failure of event %s for "
                        + "subscription %d", event.id, sub.id);
            }
            return;
        }
        try {
            if (!matched) {
                // Create a "skipped" ledger entry so we don't re-evaluate
                createLedgerEntry(event.id, sub.id, "skipped");
                return;
            }

            // Create a "pending" ledger entry: this is attempt 1
            Long ledgerId = createLedgerEntry(event.id, sub.id, "pending");
            if (ledgerId == null) return; // Another entry already exists

            // Execute routing rules
            try {
                routeEvent(event, sub, eventMap, ledgerId, 1);
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
     * Retries {@code failed} ledger entries whose next attempt is due. Entries attempted
     * since {@code tickStart} (by this tick's first pass) are excluded, so an entry runs at
     * most once per tick whatever the configured delay.
     */
    private void retryFailedEntries(List<SubscriptionWithFilters> subscriptions,
                                    Instant tickStart) {
        List<EventProcessingLedgerEntity> failedEntries = QuarkusTransaction.requiringNew().call(() ->
            EventProcessingLedgerEntity.<EventProcessingLedgerEntity>find(
                "status = ?1 AND nextAttemptAt <= ?2 "
                        + "AND (lastAttemptAt IS NULL OR lastAttemptAt < ?3) "
                        + "ORDER BY nextAttemptAt ASC", "failed", Instant.now(), tickStart)
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

            Integer attempt = beginAttempt(entry.id);
            if (attempt == null) continue; // Changed concurrently (e.g. deleted)

            Map<String, Object> eventMap;
            try {
                eventMap = buildEventMap(event, parsePayload(event));
                // An entry that never ran a routing rule may have failed before its filter
                // matched (a pre-ledger failure): evaluate the filter again.
                if (!hasRuleOutcomes(entry.id) && !filterEvaluator.matches(sub.filterExpression, eventMap)) {
                    skipLedgerEntry(entry.id);
                    continue;
                }
            } catch (Exception e) {
                failProcessing(entry.id, attempt, e);
                continue;
            }
            try {
                routeEvent(event, sub, eventMap, entry.id, attempt);
                completeLedgerEntry(entry.id);
                LOG.infof("Retry succeeded for event %s / subscription %d (attempt %d)",
                        event.id, sub.id, attempt);
            } catch (Exception e) {
                failLedgerEntry(entry.id, e.getMessage());
            }
        }
    }

    /**
     * Parses the event payload, returning null (an empty payload map) if it is not valid
     * JSON. The first pass and retries use the same rule, so a bad payload never fails a
     * retry that the first pass would have routed.
     */
    private JsonNode parsePayload(StreamEventEntity event) {
        try {
            return event.payload != null ? objectMapper.readTree(event.payload) : null;
        } catch (Exception e) {
            LOG.warnf("Failed to parse payload for event %s: %s", event.id, e.getMessage());
            return null;
        }
    }

    /**
     * Records a failure that happened outside {@link #routeEvent} as a failed
     * {@code processing} outcome of the given attempt, then fails the ledger entry.
     */
    private void failProcessing(Long ledgerId, int attempt, Exception e) {
        String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        LOG.warnf(e, "Event processing failed for ledger entry %d", ledgerId);
        QuarkusTransaction.requiringNew().run(() -> {
            RoutingOutcomeEntity outcome = new RoutingOutcomeEntity();
            outcome.ledgerId = ledgerId;
            outcome.routingType = ROUTING_TYPE_PROCESSING;
            outcome.status = "failed";
            outcome.errorMessage = truncate(error, 2000);
            outcome.attemptNumber = attempt;
            outcome.createdOn = Instant.now();
            outcome.persist();
        });
        failLedgerEntry(ledgerId, error);
    }

    /**
     * Whether a routing rule (not a {@code processing} failure) recorded an outcome for the
     * entry, i.e. whether its filter is known to have matched.
     */
    private boolean hasRuleOutcomes(Long ledgerId) {
        return QuarkusTransaction.requiringNew().call(() -> RoutingOutcomeEntity.count(
                "ledgerId = ?1 and routingType <> ?2", ledgerId, ROUTING_TYPE_PROCESSING) > 0);
    }

    // ── Ledger entry management ─────────────────────────────────

    /**
     * Creates a ledger entry. A {@code pending} entry starts attempt 1.
     *
     * @return the new entry's ID, or null if an entry already exists for the pair
     */
    private Long createLedgerEntry(UUID eventId, long subscriptionId, String status) {
        return QuarkusTransaction.requiringNew().call(() -> {
            // Check for existing entry (dedup)
            long existing = EventProcessingLedgerEntity.count(
                    "eventId = ?1 and subscriptionId = ?2", eventId, subscriptionId);
            if (existing > 0) return null;

            Instant now = Instant.now();
            EventProcessingLedgerEntity entry = new EventProcessingLedgerEntity();
            entry.eventId = eventId;
            entry.subscriptionId = subscriptionId;
            entry.status = status;
            entry.createdOn = now;
            if ("pending".equals(status)) {
                entry.attemptCount = 1;
                entry.lastAttemptAt = now;
            } else {
                entry.processedOn = now;
            }
            entry.persist();
            return entry.id;
        });
    }

    /**
     * Starts a retry attempt: the entry becomes {@code pending} (so a crash during the
     * attempt is recovered on startup), its attempt count is incremented and its last
     * attempt time set.
     *
     * @return the number of the new attempt, or null if the entry is no longer failed
     */
    private Integer beginAttempt(Long ledgerId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EventProcessingLedgerEntity entry = EventProcessingLedgerEntity.findById(ledgerId);
            if (entry == null || !"failed".equals(entry.status)) return null;
            entry.status = "pending";
            entry.attemptCount++;
            entry.lastAttemptAt = Instant.now();
            entry.nextAttemptAt = null;
            return entry.attemptCount;
        });
    }

    private void completeLedgerEntry(Long ledgerId) {
        finishLedgerEntry(ledgerId, "completed");
    }

    private void skipLedgerEntry(Long ledgerId) {
        finishLedgerEntry(ledgerId, "skipped");
    }

    private void finishLedgerEntry(Long ledgerId, String status) {
        QuarkusTransaction.requiringNew().run(() -> {
            EventProcessingLedgerEntity entry = EventProcessingLedgerEntity.findById(ledgerId);
            if (entry != null) {
                entry.status = status;
                entry.errorMessage = null;
                entry.nextAttemptAt = null;
                entry.processedOn = Instant.now();
            }
        });
    }

    /**
     * Fails the current attempt of a ledger entry. Below the cap the entry is {@code failed}
     * and due again after the backoff delay. When this was the last allowed attempt the
     * entry is {@code exhausted}: the error message says retries gave up and a single WARN
     * is logged (exhausted entries are never selected again).
     */
    private void failLedgerEntry(Long ledgerId, String errorMessage) {
        QuarkusTransaction.requiringNew().run(() -> {
            EventProcessingLedgerEntity entry = EventProcessingLedgerEntity.findById(ledgerId);
            if (entry != null) {
                int attempts = entry.attemptCount;
                boolean exhausted = attempts >= maxAttempts;
                String suffix = exhausted ? " (giving up after " + attempts + " attempts)" : "";
                String message = errorMessage != null ? errorMessage : "Unknown error";
                int room = 2000 - suffix.length();
                Instant now = Instant.now();
                entry.status = exhausted ? "exhausted" : "failed";
                entry.errorMessage = message.substring(0, Math.min(message.length(), room))
                        + suffix;
                entry.processedOn = now;
                entry.nextAttemptAt = exhausted ? null : now.plus(backoffDelay(attempts));
                if (exhausted) {
                    LOG.warnf("Giving up on event %s / subscription %d after %d failed attempts: %s",
                            entry.eventId, entry.subscriptionId, attempts, message);
                }
            }
        });
    }

    /**
     * Makes a {@code failed} or {@code exhausted} ledger entry due immediately. An exhausted
     * entry gets exactly one more attempt: it is at the cap, so if that attempt fails it is
     * exhausted again. Attempt numbers keep increasing, so the history stays intact.
     *
     * @param ledgerId the ledger entry to retry
     * @return the updated entry, or null if it does not exist
     * @throws IllegalStateException if the entry is neither failed nor exhausted
     */
    public EventProcessingLedgerEntity retryNow(Long ledgerId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EventProcessingLedgerEntity entry = EventProcessingLedgerEntity.findById(ledgerId);
            if (entry == null) return null;
            if (!"failed".equals(entry.status) && !"exhausted".equals(entry.status)) {
                throw new IllegalStateException("Ledger entry " + ledgerId + " is "
                        + entry.status + "; only failed or exhausted entries can be retried");
            }
            entry.status = "failed";
            entry.nextAttemptAt = Instant.now();
            LOG.infof("Manual retry requested for event %s / subscription %d (after %d attempts)",
                    entry.eventId, entry.subscriptionId, entry.attemptCount);
            return entry;
        });
    }

    // ── Routing ─────────────────────────────────────────────────

    private void routeEvent(StreamEventEntity event, SubscriptionWithFilters sub,
                             Map<String, Object> eventMap, Long ledgerId, int attempt) {
        if (sub.routing == null || sub.routing.isEmpty()) {
            LOG.debugf("Event %s matched subscription '%s' but no routing rules configured",
                    event.id, sub.name);
            return;
        }

        // On a retry, skip rules that already completed for this ledger entry. Outcomes do
        // not store the rule's index, so the k-th rule of a type is matched to the k-th
        // completed outcome of that type. Rules run in order and stop at the first
        // failure, so this is exact as long as the subscription's rules are unchanged.
        Map<String, Long> completedByType = QuarkusTransaction.requiringNew().call(() -> {
            Map<String, Long> counts = new HashMap<>();
            RoutingOutcomeEntity.<RoutingOutcomeEntity>list(
                    "ledgerId = ?1 and status = 'completed'", ledgerId)
                    .forEach(o -> counts.merge(o.routingType, 1L, Long::sum));
            return counts;
        });
        Map<String, Long> seenByType = new HashMap<>();

        for (RoutingRule rule : sub.routing) {
            long occurrence = seenByType.merge(rule.type(), 1L, Long::sum);
            if (occurrence <= completedByType.getOrDefault(rule.type(), 0L)) {
                LOG.debugf("Skipping routing rule %s (#%d) for event %s: already completed",
                        rule.type(), occurrence, event.id);
                continue;
            }
            try {
                RoutingOutcomeEntity outcome = switch (rule.type()) {
                    case RoutingRule.TYPE_MANAGER -> routeToManager(event);
                    case RoutingRule.TYPE_WORKFLOW_DISPATCH ->
                            routeToWorkflowDispatch(event, eventMap, ledgerId);
                    case RoutingRule.TYPE_CREATE_WORKFLOW ->
                            routeToCreateWorkflow(event, rule, ledgerId);
                    case RoutingRule.TYPE_INVOKE_ACTION -> routeToInvokeAction(event, rule);
                    default -> {
                        LOG.warnf("Unknown routing type '%s' in subscription %d",
                                rule.type(), sub.id);
                        yield null;
                    }
                };
                if (outcome != null) {
                    outcome.ledgerId = ledgerId;
                    outcome.attemptNumber = attempt;
                    outcome.routingType = rule.type();
                    outcome.createdOn = Instant.now();
                    QuarkusTransaction.requiringNew().run(() -> persistOutcome(outcome));
                }
            } catch (Exception e) {
                // Record failed outcome (routing types may supply a pre-filled one, e.g.
                // with the trace ID of the failed Manager evaluation)
                QuarkusTransaction.requiringNew().run(() -> {
                    RoutingOutcomeEntity failedOutcome = e instanceof RoutingFailedException rfe
                            ? rfe.outcome() : new RoutingOutcomeEntity();
                    failedOutcome.ledgerId = ledgerId;
                    failedOutcome.attemptNumber = attempt;
                    failedOutcome.routingType = rule.type();
                    failedOutcome.status = "failed";
                    if (failedOutcome.errorMessage == null) {
                        failedOutcome.errorMessage = e.getMessage() != null
                                ? truncate(e.getMessage(), 2000) : "Unknown error";
                    }
                    failedOutcome.createdOn = Instant.now();
                    persistOutcome(failedOutcome);
                });
                // Still throw to mark the ledger entry as failed
                throw e;
            }
        }
    }

    /**
     * Persists an outcome and its pending items, in the caller's transaction. Each routing
     * attempt persists its own outcome, so an attempt's items always stay on that attempt's
     * outcome. Items never affect the retry logic, which counts outcomes only.
     */
    private static void persistOutcome(RoutingOutcomeEntity outcome) {
        outcome.persist();
        for (RoutingOutcomeItemEntity item : outcome.pendingItems) {
            item.outcomeId = outcome.id;
            item.createdOn = outcome.createdOn;
            item.persist();
        }
        outcome.pendingItems.clear();
    }

    private static RoutingOutcomeItemEntity newItem(String itemType, String status,
                                                    String summary) {
        RoutingOutcomeItemEntity item = new RoutingOutcomeItemEntity();
        item.itemType = itemType;
        item.status = status;
        item.summary = truncate(summary, 2000);
        return item;
    }

    /**
     * Item type recorded for a Manager decision: a decision below the confidence threshold
     * is escalated whatever it asked for.
     */
    private String decisionItemType(ManagerDecision decision) {
        if (!managerService.meetsConfidenceThreshold(decision)) {
            return RoutingOutcomeItemEntity.TYPE_ESCALATED;
        }
        return switch (decision.decision() != null ? decision.decision() : "") {
            case "create_task", "script_action" -> RoutingOutcomeItemEntity.TYPE_TASK;
            case "ignore" -> RoutingOutcomeItemEntity.TYPE_IGNORED;
            case "escalate" -> RoutingOutcomeItemEntity.TYPE_ESCALATED;
            default -> RoutingOutcomeItemEntity.TYPE_DECISION;
        };
    }

    private RoutingOutcomeEntity routeToManager(StreamEventEntity event) {
        // Trace structure: event-ingested (root) → manager-evaluation → manager-decision
        // (one per decision) → task (for create_task / script_action decisions)
        TraceContext traceCtx = managerTraceRecorder.startTrace("manager",
                "Manager evaluation: " + event.type + " — " + event.ref,
                "Event: " + event.type + " — " + event.ref, event);
        Long evalNodeId = managerTraceRecorder.addNode(traceCtx, "manager-evaluation",
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
        managerTraceRecorder.completeNode(evalNodeId, "completed", evaluation.activityLogId());

        List<ManagerDecision> decisions = evaluation.decisions();
        if (decisions.isEmpty()) {
            LOG.debugf("Manager returned no decisions for stream event %s", event.id);
            outcome.summary = "No decisions";
            managerTraceRecorder.completeTrace(traceCtx, "completed");
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
            String label = ManagerTraceRecorder.decisionLabel(decision);
            if (summaryBuilder.length() > 0) summaryBuilder.append("; ");
            summaryBuilder.append(label);

            Long decisionNodeId = managerTraceRecorder.addNode(traceCtx, "manager-decision",
                    managerTraceRecorder.decisionNodeSummary(decision));
            RoutingOutcomeItemEntity item = newItem(decisionItemType(decision), "completed",
                    label + " — " + decision.reasoning());
            item.traceNodeId = decisionNodeId;
            outcome.pendingItems.add(item);
            if (traceCtx != null && decisionNodeId != null) {
                traceCtx.push(decisionNodeId);
            }
            try {
                ManagerDecisionResult result = processManagerDecision(event, decision, traceCtx);
                managerTraceRecorder.completeNode(decisionNodeId, "completed",
                        result != null ? result.activityLogId() : null);
                if (result != null) {
                    item.projectId = result.projectId();
                    item.taskId = result.taskId();
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
                item.status = "failed";
                item.errorMessage = truncate(error, 2000);
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
        managerTraceRecorder.failNode(evalNodeId, error, activityLogId);
        managerTraceRecorder.completeTrace(traceCtx, "failed");
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
                managerTraceRecorder.completeTrace(traceCtx, "completed");
            }
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

    /**
     * Offers the event to the workflow runs parked at receive-event nodes. Records one
     * {@code workflow-resumed} item per resumed run, or a {@code no-match} item when the event
     * resumed nothing.
     */
    private RoutingOutcomeEntity routeToWorkflowDispatch(StreamEventEntity event,
                                                          Map<String, Object> eventMap,
                                                          Long ledgerId) {
        List<ResumedRun> resumed = workflowEventDispatcher.dispatchStreamEvent(event.type,
                eventMap, new EventOrigin(event.id, ledgerId));

        RoutingOutcomeEntity outcome = new RoutingOutcomeEntity();
        outcome.status = "completed";
        if (resumed.isEmpty()) {
            outcome.summary = "No waiting workflow run matched the event";
            outcome.pendingItems.add(newItem(RoutingOutcomeItemEntity.TYPE_NO_MATCH, "completed",
                    "No workflow run was waiting for a " + event.type + " event that matched"));
            return outcome;
        }
        // A run that matched but failed to resume is a failed item. The outcome itself stays
        // completed: failing it would retry the rule and re-dispatch the event to every run.
        long failedCount = resumed.stream().filter(ResumedRun::failed).count();
        long resumedCount = resumed.size() - failedCount;
        outcome.summary = "Resumed " + resumedCount + " workflow run"
                + (resumedCount == 1 ? "" : "s")
                + (failedCount > 0 ? "; " + failedCount + " failed to resume" : "");
        for (ResumedRun run : resumed) {
            RoutingOutcomeItemEntity item = newItem(RoutingOutcomeItemEntity.TYPE_WORKFLOW_RESUMED,
                    run.failed() ? "failed" : "completed",
                    (run.failed() ? "Failed to resume workflow run " : "Resumed workflow run ")
                            + run.runId() + " at receive-event node " + run.nodeId());
            if (run.failed()) {
                item.errorMessage = truncate(run.errorMessage(), 2000);
            }
            item.projectId = run.projectId();
            item.workflowRunId = run.runId();
            item.traceNodeId = run.traceNodeId();
            outcome.pendingItems.add(item);
            // Backward compatibility: the outcome holds the first project and trace
            if (outcome.projectId == null) {
                outcome.projectId = run.projectId();
            }
            if (outcome.traceId == null) {
                outcome.traceId = run.traceId();
            }
        }
        return outcome;
    }

    private RoutingOutcomeEntity routeToCreateWorkflow(StreamEventEntity event, RoutingRule rule,
                                                        Long ledgerId) {
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
            eventNode.put("id", event.id.toString());
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

        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(projectId, rule.workflowDefinitionId(),
                        extraContext, new EventOrigin(event.id, ledgerId)));
        LOG.infof("Created workflow (definition %d) for event %s on project %d",
                rule.workflowDefinitionId(), event.id, projectId);

        sseEvents.fire(SseEvent.projectUpdated(projectId));

        RoutingOutcomeEntity outcome = new RoutingOutcomeEntity();
        outcome.status = "completed";
        outcome.summary = "Created workflow from definition " + rule.workflowDefinitionId();
        outcome.projectId = projectId;
        outcome.traceId = run != null ? run.traceId : null;
        RoutingOutcomeItemEntity item = newItem(RoutingOutcomeItemEntity.TYPE_WORKFLOW_RUN,
                "completed", "Workflow run from definition " + rule.workflowDefinitionId());
        item.projectId = projectId;
        item.workflowRunId = run != null ? run.id : null;
        outcome.pendingItems.add(item);
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
            managerTraceRecorder.completeTrace(traceCtx, "failed");
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
        RoutingOutcomeItemEntity item = newItem(RoutingOutcomeItemEntity.TYPE_TASK, "completed",
                "Task: " + actionType.name);
        item.projectId = projectId;
        item.taskId = taskId;
        outcome.pendingItems.add(item);
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
        if (event.id != null) {
            map.put("id", event.id.toString());
        }
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
