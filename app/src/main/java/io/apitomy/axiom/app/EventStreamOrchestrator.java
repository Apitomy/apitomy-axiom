package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.filters.EventSourceFilters;
import io.apitomy.axiom.core.filters.SubscriptionFilterEvaluator;
import io.apitomy.axiom.core.filters.FilterResult;
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

        for (SubscriptionWithFilters sub : subscriptions) {
            FilterResult result = filterEvaluator.evaluate(
                    sub.filters, event.type, event.connectionId, event.ref, payloadNode);

            if (result.allowed()) {
                routeEvent(event, sub, payloadNode);
            }
        }
    }

    private void routeEvent(StreamEventEntity event, SubscriptionWithFilters sub,
                             JsonNode payloadNode) {
        // Dispatch to workflow receive-event nodes
        try {
            Map<String, Object> eventMap = buildEventMap(event, payloadNode);
            workflowEventDispatcher.dispatchStreamEvent(event.type, eventMap);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to dispatch stream event %s to workflows", event.id);
        }

        // Manager triage — evaluate the stream event for decisions.
        // Full decision processing (creating tasks, escalating, etc.) still lives
        // in the old PipelineOrchestrator. For now, just invoke the Manager and log.
        try {
            List<ManagerDecision> decisions = managerService.evaluateStreamEvent(event);
            if (decisions != null && !decisions.isEmpty()) {
                LOG.infof("Manager returned %d decisions for stream event %s",
                        decisions.size(), event.id);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to evaluate stream event %s via Manager", event.id);
        }

        LOG.debugf("Event %s matched subscription '%s' (id=%d)",
                event.id, sub.name, sub.id);
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
        return entities.stream().map(e -> {
            EventSourceFilters filters = null;
            if (e.filters != null && !e.filters.isBlank()) {
                try {
                    filters = objectMapper.readValue(e.filters, EventSourceFilters.class);
                } catch (Exception ex) {
                    LOG.warnf("Failed to parse filters for subscription %d: %s",
                            e.id, ex.getMessage());
                }
            }
            return new SubscriptionWithFilters(e.id, e.name, e.labels, filters);
        }).toList();
    }

    record SubscriptionWithFilters(long id, String name, List<String> labels,
                                    EventSourceFilters filters) {}
}
