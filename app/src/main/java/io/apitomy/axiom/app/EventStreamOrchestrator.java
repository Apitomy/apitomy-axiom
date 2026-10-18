package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.axiom.core.events.model.RoutingRule;
import io.apitomy.axiom.core.filters.SubscriptionFilterResult;
import io.apitomy.axiom.core.filters.SubscriptionFilterEvaluator;
import io.apitomy.axiom.core.logging.LogContext;
import io.apitomy.axiom.core.services.WorkspaceService;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes stream events against enabled subscriptions using a durable
 * processing ledger. The ledger tracks (event, subscription) pairs with
 * status (pending/completed/skipped/failed/exhausted/filter_error), surviving restarts and
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
    WorkflowExecutionService workflowExecutionService;

    @Inject
    ScriptExecutionService scriptExecutionService;

    @Inject
    Event<SseEvent> sseEvents;

    /**
     * Ledger changes committed during the current tick; one {@code stream-processing} SSE event is
     * sent at the end of the tick when non-zero (coalesced, so a batch produces one event).
     */
    private final AtomicInteger ledgerChanges = new AtomicInteger();

    @Inject
    WorkspaceService workspaceService;

    @Inject
    io.apitomy.axiom.core.tracing.TraceService traceService;

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

    /**
     * Fails startup when the retry configuration is invalid (rather than clamping it, so a
     * misconfiguration is never silently replaced by different behavior).
     */
    void onStart(@Observes StartupEvent event) {
        validateRetryConfig(maxAttempts, retryInitialDelay, retryMaxDelay);
    }

    /**
     * Validates the retry configuration.
     *
     * @param maxAttempts  {@code axiom.stream-pipeline.max-attempts}, at least 1
     * @param initialDelay {@code axiom.stream-pipeline.retry-initial-delay}, greater than zero
     * @param maxDelay     {@code axiom.stream-pipeline.retry-max-delay}, at least the initial delay
     * @throws IllegalStateException naming the first invalid property
     */
    static void validateRetryConfig(int maxAttempts, Duration initialDelay, Duration maxDelay) {
        if (maxAttempts < 1) {
            throw new IllegalStateException("axiom.stream-pipeline.max-attempts must be at least 1, was "
                    + maxAttempts);
        }
        if (initialDelay == null || initialDelay.isZero() || initialDelay.isNegative()) {
            throw new IllegalStateException(
                    "axiom.stream-pipeline.retry-initial-delay must be greater than zero, was "
                    + initialDelay);
        }
        if (maxDelay == null || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalStateException("axiom.stream-pipeline.retry-max-delay (" + maxDelay
                    + ") must not be less than axiom.stream-pipeline.retry-initial-delay ("
                    + initialDelay + ")");
        }
    }

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
        try {
            runTick();
        } finally {
            notifyLedgerChanges();
        }
    }

    /**
     * Sends one {@code stream-processing} SSE event if ledger entries changed since the last
     * notification. Every ledger change runs in its own committed transaction, so clients
     * reloading on this event see the changes.
     */
    void notifyLedgerChanges() {
        int changes = ledgerChanges.getAndSet(0);
        if (changes > 0) {
            sseEvents.fire(SseEvent.streamProcessing(changes));
        }
    }

    private void runTick() {
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
                    ledgerChanges.addAndGet((int) (recovered + exhausted));
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
                try (LogContext ignored = LogContext.create().eventId(event.id)) {
                    processEventForSubscription(event, sub);
                }
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
            SubscriptionFilterResult result = filterEvaluator.evaluateFilter(sub.filterExpression, eventMap);
            if (result.isError()) {
                Long ledgerId = createLedgerEntry(event.id, sub.id, EventProcessingLedgerEntity.PENDING);
                if (ledgerId != null) {
                    recordFilterError(ledgerId, event.id, sub, result.errorMessage());
                }
                return;
            }
            matched = result.matched();
        } catch (Exception e) {
            try {
                Long ledgerId = createLedgerEntry(event.id, sub.id, EventProcessingLedgerEntity.PENDING);
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
                createLedgerEntry(event.id, sub.id, EventProcessingLedgerEntity.SKIPPED);
                return;
            }

            // Create a "pending" ledger entry: this is attempt 1
            Long ledgerId = createLedgerEntry(event.id, sub.id, EventProcessingLedgerEntity.PENDING);
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
                        + "ORDER BY nextAttemptAt ASC", EventProcessingLedgerEntity.FAILED, Instant.now(), tickStart)
                .page(0, BATCH_SIZE).list()
        );

        for (EventProcessingLedgerEntity entry : failedEntries) {
            if (shuttingDown) break;
            try (LogContext ignored = LogContext.create().eventId(entry.eventId)) {
                retryFailedEntry(entry, subscriptions);
            }
        }
    }

    /**
     * Retries a single failed ledger entry (one iteration of {@link #retryFailedEntries}).
     */
    private void retryFailedEntry(EventProcessingLedgerEntity entry,
                                  List<SubscriptionWithFilters> subscriptions) {
        SubscriptionWithFilters sub = subscriptions.stream()
                .filter(s -> s.id == entry.subscriptionId)
                .findFirst().orElse(null);
        if (sub == null) return; // Subscription no longer enabled or deleted

        StreamEventEntity event = QuarkusTransaction.requiringNew().call(() ->
                StreamEventEntity.findById(entry.eventId));
        if (event == null) {
            // Event was deleted (retention); clean up the ledger entry
            QuarkusTransaction.requiringNew().run(() -> {
                EventProcessingLedgerEntity e = EventProcessingLedgerEntity.findById(entry.id);
                if (e != null) e.delete();
            });
            return;
        }

        Integer attempt = beginAttempt(entry.id);
        if (attempt == null) return; // Changed concurrently (e.g. deleted)

        Map<String, Object> eventMap;
        try {
            eventMap = buildEventMap(event, parsePayload(event));
            // An entry that never ran a routing rule may have failed before its filter
            // matched (a pre-ledger failure): evaluate the filter again.
            if (!hasRuleOutcomes(entry.id)) {
                SubscriptionFilterResult result = filterEvaluator.evaluateFilter(sub.filterExpression, eventMap);
                if (result.isError()) {
                    recordFilterError(entry.id, event.id, sub, result.errorMessage());
                    return;
                }
                if (!result.matched()) {
                    skipLedgerEntry(entry.id);
                    return;
                }
            }
        } catch (Exception e) {
            failProcessing(entry.id, attempt, e);
            return;
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
     * Marks a ledger entry {@code filter_error}: the subscription's filter could not be evaluated
     * against the event. No route runs (fail closed). Filter errors are usually deterministic, so the
     * entry is not retried automatically; a manual retry or a change of the filter makes it due again.
     *
     * <p>The error came from the filter in the tick's subscription snapshot. If the subscription
     * was modified since the snapshot was taken (e.g. its filter was fixed while the tick evaluated
     * the old one), {@link #retryFilterErrors} already ran and found the entry still pending, so the
     * entry is made due ({@code failed}) instead, to be re-evaluated with the current filter. The
     * subscription row is locked first so a concurrent update either commits before the check (and
     * is seen) or runs its {@link #retryFilterErrors} after this transaction commits (and re-queues
     * the entry). Only a {@code pending} entry is changed.</p>
     */
    private void recordFilterError(Long ledgerId, UUID eventId, SubscriptionWithFilters sub,
                                   String errorMessage) {
        String message = "Filter evaluation failed: "
                + (errorMessage != null ? errorMessage : "Unknown error");
        LOG.warnf("Filter of subscription %d ('%s') could not be evaluated for event %s: %s",
                sub.id, sub.name, eventId, errorMessage);
        QuarkusTransaction.requiringNew().run(() -> {
            EventSubscriptionEntity current = EventSubscriptionEntity.findById(sub.id,
                    LockModeType.PESSIMISTIC_WRITE);
            boolean stale = current != null && current.modifiedOn != null
                    && (sub.modifiedOn == null || current.modifiedOn.isAfter(sub.modifiedOn));
            Instant now = Instant.now();
            if (stale) {
                LOG.infof("Subscription %d changed while its filter was evaluated for event %s; "
                        + "re-evaluating", sub.id, eventId);
                EventProcessingLedgerEntity.update(
                        "status = ?1, errorMessage = ?2, nextAttemptAt = ?3, processedOn = ?3 "
                        + "where id = ?4 and status = ?5",
                        EventProcessingLedgerEntity.FAILED, truncate(message, 2000), now, ledgerId,
                        EventProcessingLedgerEntity.PENDING);
            } else {
                EventProcessingLedgerEntity.update(
                        "status = ?1, errorMessage = ?2, nextAttemptAt = null, processedOn = ?3 "
                        + "where id = ?4 and status = ?5",
                        EventProcessingLedgerEntity.FILTER_ERROR, truncate(message, 2000), now,
                        ledgerId, EventProcessingLedgerEntity.PENDING);
            }
        });
        ledgerChanges.incrementAndGet();
    }

    /**
     * Makes every {@code filter_error} ledger entry of an enabled subscription due for
     * re-evaluation, e.g. after its filter was changed. Re-evaluating a filter is not a routing
     * attempt, so the attempt count is reset: the re-evaluation becomes attempt 1. Must be called
     * in the transaction that changed the subscription, after the change was flushed (see
     * {@link #recordFilterError}).
     *
     * @param subscriptionId the subscription
     * @return the number of entries made due; 0 if the subscription is disabled or missing
     */
    public int retryFilterErrors(long subscriptionId) {
        EventSubscriptionEntity subscription = EventSubscriptionEntity.findById(subscriptionId);
        if (subscription == null || !subscription.enabled) {
            return 0;
        }
        return EventProcessingLedgerEntity.update(
                "status = ?1, nextAttemptAt = ?2, attemptCount = 0 where subscriptionId = ?3 and status = ?4",
                EventProcessingLedgerEntity.FAILED, Instant.now(), subscriptionId,
                EventProcessingLedgerEntity.FILTER_ERROR);
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
        Long created = QuarkusTransaction.requiringNew().call(() -> {
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
            if (EventProcessingLedgerEntity.PENDING.equals(status)) {
                entry.attemptCount = 1;
                entry.lastAttemptAt = now;
            } else {
                entry.processedOn = now;
            }
            entry.persist();
            return entry.id;
        });
        if (created != null) ledgerChanges.incrementAndGet();
        return created;
    }

    /**
     * Starts a retry attempt: the entry becomes {@code pending} (so a crash during the
     * attempt is recovered on startup), its attempt count is incremented and its last
     * attempt time set. This is a single conditional update, so it only succeeds while the
     * entry is still {@code failed}: a concurrent manual retry or another node that started
     * the attempt first makes it change no row.
     *
     * @param ledgerId the ledger entry
     * @return the number of the new attempt, or null if the entry is no longer failed
     */
    Integer beginAttempt(Long ledgerId) {
        Integer attempt = QuarkusTransaction.requiringNew().call(() -> {
            int updated = EventProcessingLedgerEntity.update(
                    "status = 'pending', attemptCount = attemptCount + 1, lastAttemptAt = ?1, "
                    + "nextAttemptAt = null where id = ?2 and status = 'failed'",
                    Instant.now(), ledgerId);
            if (updated == 0) return null;
            EventProcessingLedgerEntity entry = EventProcessingLedgerEntity.findById(ledgerId);
            return entry != null ? entry.attemptCount : null;
        });
        if (attempt != null) ledgerChanges.incrementAndGet();
        return attempt;
    }

    private void completeLedgerEntry(Long ledgerId) {
        finishLedgerEntry(ledgerId, EventProcessingLedgerEntity.COMPLETED);
    }

    private void skipLedgerEntry(Long ledgerId) {
        finishLedgerEntry(ledgerId, EventProcessingLedgerEntity.SKIPPED);
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
        ledgerChanges.incrementAndGet();
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
                entry.status = exhausted ? EventProcessingLedgerEntity.EXHAUSTED : EventProcessingLedgerEntity.FAILED;
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
        ledgerChanges.incrementAndGet();
    }

    /**
     * Makes a {@code failed}, {@code exhausted} or {@code filter_error} ledger entry due immediately
     * (a {@code filter_error} entry re-evaluates the filter). An exhausted
     * entry gets exactly one more attempt: it is at the cap, so if that attempt fails it is
     * exhausted again. Attempt numbers keep increasing, so the history stays intact.
     *
     * @param ledgerId the ledger entry to retry
     * @return the updated entry, or null if it does not exist
     * @throws IllegalStateException if the entry is neither failed nor exhausted
     */
    public EventProcessingLedgerEntity retryNow(Long ledgerId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            // A single conditional update: an attempt started concurrently (status pending)
            // is never made due again nor has its attempt count rolled back.
            // Re-evaluating a filter is not a routing attempt: a filter_error entry restarts its
            // attempt count (attemptCount = 0, so the re-evaluation is attempt 1).
            int updated = EventProcessingLedgerEntity.update(
                    "status = ?1, nextAttemptAt = ?2, "
                    + "attemptCount = case when status = ?3 then 0 else attemptCount end "
                    + "where id = ?4 and status in (?1, ?5, ?3)",
                    EventProcessingLedgerEntity.FAILED, Instant.now(),
                    EventProcessingLedgerEntity.FILTER_ERROR, ledgerId,
                    EventProcessingLedgerEntity.EXHAUSTED);
            EventProcessingLedgerEntity entry = EventProcessingLedgerEntity.findById(ledgerId);
            if (entry == null) return null;
            if (updated == 0) {
                throw new IllegalStateException("Ledger entry " + ledgerId + " is "
                        + entry.status + "; only failed, exhausted or filter_error entries can be retried");
            }
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
            // Each rule starts its own trace: a per-rule context scopes the trace ID to it.
            try (LogContext ruleLog = LogContext.create()) {
                routeRule(event, sub, rule, eventMap, ledgerId, attempt);
            }
        }
    }

    /**
     * Runs a single routing rule and persists its outcome (completed or failed).
     */
    private void routeRule(StreamEventEntity event, SubscriptionWithFilters sub, RoutingRule rule,
                           Map<String, Object> eventMap, Long ledgerId, int attempt) {
        try {
            RoutingOutcomeEntity outcome = switch (rule.type()) {
                case RoutingRule.TYPE_WORKFLOW_DISPATCH ->
                        routeToWorkflowDispatch(event, eventMap, ledgerId);
                case RoutingRule.TYPE_CREATE_WORKFLOW ->
                        routeToCreateWorkflow(event, rule, ledgerId);
                default -> throw new IllegalStateException(
                        "Unsupported routing type: " + rule.type());
            };
            if (outcome != null) {
                outcome.ledgerId = ledgerId;
                outcome.attemptNumber = attempt;
                outcome.routingType = rule.type();
                outcome.createdOn = Instant.now();
                QuarkusTransaction.requiringNew().run(() -> persistOutcome(outcome));
            }
        } catch (Exception e) {
            // Record failed outcome
            QuarkusTransaction.requiringNew().run(() -> {
                RoutingOutcomeEntity failedOutcome = new RoutingOutcomeEntity();
                failedOutcome.ledgerId = ledgerId;
                failedOutcome.attemptNumber = attempt;
                failedOutcome.routingType = rule.type();
                failedOutcome.status = "failed";
                failedOutcome.errorMessage = e.getMessage() != null
                        ? truncate(e.getMessage(), 2000) : "Unknown error";
                failedOutcome.createdOn = Instant.now();
                persistOutcome(failedOutcome);
            });
            // Message only: the caller logs the stack trace once ("Routing failed for event"),
            // since not every routing failure passes through here.
            LOG.warnf("Routing rule %s failed for event %s: %s", rule.type(), event.id,
                    e.getMessage());
            // Still throw to mark the ledger entry as failed
            throw e;
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

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    /**
     * Completes the trace with the given status, logging (not propagating) any failure.
     */
    private void completeTraceQuietly(TraceContext traceCtx, String status) {
        if (traceCtx == null) return;
        try {
            traceService.completeTrace(traceCtx.traceId(), status);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to complete trace %s", traceCtx.traceId());
        }
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
            item.subjectId = run.subjectId();
            item.workflowRunId = run.runId();
            item.traceNodeId = run.traceNodeId();
            outcome.pendingItems.add(item);
            // Backward compatibility: the outcome holds the first subject and trace
            if (outcome.subjectId == null) {
                outcome.subjectId = run.subjectId();
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
        Long subjectId = findOrCreateSubjectForEvent(event, null);
        WorkflowRunEntity run;
        try {
            run = startRunFromEvent(event, subjectId, rule.workflowDefinitionId(),
                    RunRequest.EMPTY, ledgerId);
        } catch (WorkflowRunConflictException e) {
            String summary = "Skipped: subject already has an active run of definition "
                    + rule.workflowDefinitionId();
            LOG.infof("%s (event %s, subject %d)", summary, event.id, subjectId);
            RoutingOutcomeEntity skipped = new RoutingOutcomeEntity();
            skipped.status = "completed";
            skipped.summary = summary;
            skipped.subjectId = subjectId;
            RoutingOutcomeItemEntity item = newItem(RoutingOutcomeItemEntity.TYPE_WORKFLOW_RUN,
                    "skipped", summary);
            item.subjectId = subjectId;
            skipped.pendingItems.add(item);
            return skipped;
        }

        RoutingOutcomeEntity outcome = new RoutingOutcomeEntity();
        outcome.status = "completed";
        outcome.summary = "Created workflow from definition " + rule.workflowDefinitionId();
        outcome.subjectId = subjectId;
        outcome.traceId = run != null ? run.traceId : null;
        RoutingOutcomeItemEntity item = newItem(RoutingOutcomeItemEntity.TYPE_WORKFLOW_RUN,
                "completed", "Workflow run from definition " + rule.workflowDefinitionId());
        item.subjectId = subjectId;
        item.workflowRunId = run != null ? run.id : null;
        outcome.pendingItems.add(item);
        return outcome;
    }

    /**
     * Starts a run of a workflow definition for a stream event: resolves (or creates) the event's subject,
     * and starts the run in its own transaction. The run's {@code event} input is the event when its Start node
     * declares {@code event}.
     * Used both by {@code create-workflow} routing rules and by manual "run from event" requests.
     *
     * @param event        the stream event
     * @param definitionId the workflow definition to run
     * @param request      run title and labels
     * @param ledgerId     the processing (ledger) entry that triggered the run; null for manual runs
     * @return the started run
     * @throws WorkflowRunConflictException if the subject already has an active run of the definition
     */
    public WorkflowRunEntity startRunFromEvent(StreamEventEntity event, long definitionId,
                                               RunRequest request, Long ledgerId) {
        Long subjectId = findOrCreateSubjectForEvent(event, null);
        return startRunFromEvent(event, subjectId, definitionId, request, ledgerId);
    }

    private WorkflowRunEntity startRunFromEvent(StreamEventEntity event, Long subjectId, long definitionId,
                                                RunRequest request, Long ledgerId) {
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, definitionId, request,
                        new EventOrigin(event.id, ledgerId)));
        LOG.infof("Created workflow (definition %d) for event %s on subject %d",
                definitionId, event.id, subjectId);

        sseEvents.fire(SseEvent.subjectUpdated(subjectId));
        return run;
    }

    private Long findOrCreateSubjectForEvent(StreamEventEntity event, UUID traceId) {
        return QuarkusTransaction.requiringNew().call(() ->
                findOrCreateSubjectForStreamEvent(event, traceId).id);
    }

    /**
     * The {@code owner/repo} an event belongs to, for GitHub events (derived from the event ref).
     *
     * @param event the stream event
     * @return the repository, or null for non-GitHub events or when it cannot be determined
     */
    static String repositoryFor(StreamEventEntity event) {
        if (!"github".equals(event.source)) {
            return null;
        }
        return io.apitomy.axiom.events.github.v2.GitHubUrls.repositoryFromRef(event.ref).orElse(null);
    }

    private SubjectEntity findOrCreateSubjectForStreamEvent(StreamEventEntity event,
                                                            UUID traceId) {
        // Try to find existing subject by ref
        SubjectEntity subject = SubjectEntity.find("ref", event.ref).firstResult();
        if (subject != null) {
            // Subjects created before the repository was recorded get it filled in now
            if (subject.repository == null || subject.repository.isBlank()) {
                String repository = repositoryFor(event);
                if (repository != null) {
                    subject.repository = repository;
                    subject.updatedOn = Instant.now();
                }
            }
            return subject;
        }

        // Auto-create with metadata from the payload
        subject = newSubjectFor(event);
        subject.persist();

        LOG.infof("Auto-created subject %d for %s", subject.id, event.ref);

        logActivity(subject.id, null, event.id, "subject-created",
                "Subject auto-created from " + event.type + " event", traceId);
        addThreadEntry(subject.id, "system", "message",
                "Subject created from " + event.source + " event: " + event.type);

        // Ensure the workspace directory exists
        try {
            workspaceService.ensureWorkspace(subject);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to create workspace for subject %d", subject.id);
        }

        return subject;
    }

    /**
     * The {@code subject} standard input a run started from this event would receive, without creating or
     * changing anything: the event's existing subject (with a missing GitHub repository filled in, as run
     * creation does), or a preview of the subject run creation would create (with a null {@code id}).
     *
     * @param event the stream event
     * @return the subject input value
     */
    public Map<String, Object> subjectValueForEvent(StreamEventEntity event) {
        SubjectEntity existing = SubjectEntity.find("ref", event.ref).firstResult();
        if (existing == null) {
            return StandardInputs.subjectValue(newSubjectFor(event));
        }
        Map<String, Object> value = StandardInputs.subjectValue(existing);
        if (value.get("repository") == null) {
            value.put("repository", repositoryFor(event));
        }
        return value;
    }

    /**
     * The event map subscription filters are evaluated against.
     *
     * @param event the stream event
     * @return the event map exposed as {@code event} to filter expressions
     */
    public Map<String, Object> filterEventMap(StreamEventEntity event) {
        return buildEventMap(event, parsePayload(event));
    }

    /**
     * Builds, without persisting, the subject auto-created for an event that has none: ref, source, GitHub
     * repository, type from the event type, and name and body from the normalized payload.
     *
     * @param event the stream event
     * @return the unsaved subject
     */
    private SubjectEntity newSubjectFor(StreamEventEntity event) {
        SubjectEntity subject = new SubjectEntity();
        subject.ref = event.ref;
        subject.refSource = event.source;
        subject.repository = repositoryFor(event);
        subject.type = determineSubjectType(event.type);
        subject.createdOn = Instant.now();
        subject.updatedOn = Instant.now();

        // Extract title and body from the normalized payload
        try {
            JsonNode payload = objectMapper.readTree(event.payload);
            // The normalized payload uses "issue.title" or "pullRequest.title"
            String title = payload.path("issue").path("title").asText(null);
            if (title == null) title = payload.path("pullRequest").path("title").asText(null);
            subject.name = title != null ? title : event.ref;

            String body = payload.path("issue").path("body").asText(null);
            if (body == null) body = payload.path("pullRequest").path("body").asText(null);
            subject.body = body;
        } catch (Exception e) {
            subject.name = event.ref;
        }
        return subject;
    }

    private String determineSubjectType(String eventType) {
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
        map.put("eventSourceId", event.eventSourceId);
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
                            e.processEventsFrom != null ? e.processEventsFrom : Instant.EPOCH,
                            e.modifiedOn);
                })
                .toList();
    }

    record SubscriptionWithFilters(long id, String name, List<String> labels,
                                    String filterExpression, List<RoutingRule> routing,
                                    Instant processEventsFrom, Instant modifiedOn) {}

    // ── Activity and thread logging ────────────────────────────────

    private Long logActivity(Long subjectId, Long taskId, UUID eventId,
                             String entryType, String summary, UUID traceId) {
        ActivityLogEntity log = new ActivityLogEntity();
        log.subjectId = subjectId;
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

    private void addThreadEntry(Long subjectId, String authorType, String entryType,
                                  String content) {
        ThreadEntryEntity entry = new ThreadEntryEntity();
        entry.subjectId = subjectId;
        entry.authorType = authorType;
        entry.entryType = entryType;
        entry.content = content;
        entry.createdOn = Instant.now();
        entry.persist();
    }
}
