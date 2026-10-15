package io.apitomy.axiom.app.expressions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.NullNode;
import io.apitomy.axiom.app.EventStreamOrchestrator;
import io.apitomy.axiom.app.StandardInputs;
import io.apitomy.axiom.app.WorkflowEventMapper;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.flow.engine.ConditionEvaluator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Evaluates EL expressions the way the workflow engine does, per authoring scope.
 *
 * <table>
 * <caption>Scopes and their EL variables</caption>
 * <tr><th>Scope</th><th>Kind</th><th>Variables</th></tr>
 * <tr><td>{@code edge-condition}</td><td>boolean</td><td>{@code context}</td></tr>
 * <tr><td>{@code action-input}</td><td>value</td><td>{@code context}</td></tr>
 * <tr><td>{@code human-task-title}</td><td>value</td><td>{@code context}</td></tr>
 * <tr><td>{@code correlation-subscription-key}</td><td>value</td><td>{@code context}</td></tr>
 * <tr><td>{@code correlation-event-key}</td><td>value</td><td>{@code event} only</td></tr>
 * <tr><td>{@code receive-event-match}</td><td>boolean</td><td>{@code context}, {@code event}</td></tr>
 * <tr><td>{@code receive-event-output}</td><td>value</td><td>{@code context}, {@code event}</td></tr>
 * <tr><td>{@code subscription-filter}</td><td>boolean</td><td>{@code event} only</td></tr>
 * </table>
 *
 * <p>{@code event} is {@link WorkflowEventMapper#toEventMap(StreamEventEntity, ObjectMapper)} of the stored
 * stream event; for {@code subscription-filter} it is the map subscription matching uses. Context scopes given
 * an event ID see the standard inputs a run started from that event would receive: {@code context.event} and
 * {@code context.subject} (see {@link StandardInputs}), unless the supplied context already has them.</p>
 */
@ApplicationScoped
public class ExpressionService {

    /**
     * An authoring scope for an EL expression.
     */
    public enum Scope {
        EDGE_CONDITION("edge-condition", true, false, true, "an edge condition (boolean; sees context)"),
        ACTION_INPUT("action-input", true, false, false, "an action node input mapping (value; sees context)"),
        HUMAN_TASK_TITLE("human-task-title", true, false, false,
                "a human-task title or description (value; sees context)"),
        CORRELATION_SUBSCRIPTION_KEY("correlation-subscription-key", true, false, false,
                "a receive-event correlationKey.subscriptionKey (value; sees context)"),
        CORRELATION_EVENT_KEY("correlation-event-key", false, true, false,
                "a receive-event correlationKey.eventKey (value; sees only the received event)"),
        RECEIVE_EVENT_MATCH("receive-event-match", true, true, true,
                "a receive-event match condition (boolean; sees context and the received event)"),
        RECEIVE_EVENT_OUTPUT("receive-event-output", true, true, false,
                "a receive-event output mapping (value; sees context and the received event)"),
        SUBSCRIPTION_FILTER("subscription-filter", false, true, true,
                "a subscription filterExpression (boolean; sees only the event)");

        private final String jsonName;
        private final boolean context;
        private final boolean event;
        private final boolean bool;
        private final String description;

        Scope(String jsonName, boolean context, boolean event, boolean bool, String description) {
            this.jsonName = jsonName;
            this.description = description;
            this.context = context;
            this.event = event;
            this.bool = bool;
        }

        /**
         * @return the JSON name of the scope
         */
        public String jsonName() {
            return jsonName;
        }

        /**
         * @return where an expression of this scope is used, its result kind and its variables
         */
        public String description() {
            return description;
        }

        /**
         * @return whether the expression sees {@code context}
         */
        public boolean usesContext() {
            return context;
        }

        /**
         * @return whether the expression sees {@code event}
         */
        public boolean usesEvent() {
            return event;
        }

        /**
         * @return whether the expression is a boolean condition
         */
        public boolean isBoolean() {
            return bool;
        }

        /**
         * Finds a scope by its JSON name.
         *
         * @param name the JSON name
         * @return the scope
         * @throws IllegalArgumentException if the name is unknown
         */
        public static Scope fromName(String name) {
            return Arrays.stream(values()).filter(s -> s.jsonName.equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown scope '" + name + "'; valid scopes: "
                            + Arrays.stream(values()).map(Scope::jsonName).collect(Collectors.joining(", "))));
        }
    }

    /**
     * The outcome of an evaluation.
     *
     * @param ok    whether evaluation succeeded
     * @param value the value, when ok
     * @param error the error message, when not ok
     */
    public record Result(boolean ok, JsonNode value, String error) {

        static Result failure(String error) {
            return new Result(false, null, error);
        }
    }

    @Inject
    ObjectMapper mapper;

    @Inject
    EventStreamOrchestrator orchestrator;

    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    /**
     * Evaluates an expression in a scope.
     *
     * @param expression the EL expression
     * @param scope      the scope JSON name
     * @param eventId    optional stored stream event ID
     * @param context    optional context object
     * @return the result; {@code ok = false} for EL errors and bad event IDs or context
     * @throws IllegalArgumentException for a blank expression or an unknown scope
     */
    @SuppressWarnings("unchecked")
    public Result evaluate(String expression, String scope, String eventId, JsonNode context) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Expression must not be blank");
        }
        Scope s = Scope.fromName(scope);
        if (context != null && !context.isNull() && !context.isObject()) {
            return Result.failure("context must be a JSON object");
        }
        Map<String, Object> contextMap = context == null || context.isNull()
                ? new HashMap<>() : new HashMap<>(mapper.convertValue(context, Map.class));

        Map<String, Object> event = null;
        if (eventId != null && !eventId.isBlank()) {
            UUID id;
            try {
                id = UUID.fromString(eventId);
            } catch (IllegalArgumentException e) {
                return Result.failure("Invalid eventId '" + eventId + "'");
            }
            StreamEventEntity entity = StreamEventEntity.findById(id);
            if (entity == null) {
                return Result.failure("Stream event '" + eventId + "' not found");
            }
            event = s == Scope.SUBSCRIPTION_FILTER ? orchestrator.filterEventMap(entity)
                    : WorkflowEventMapper.toEventMap(entity, mapper);
            if (s.usesContext()) {
                // The standard inputs a run started from this event would receive.
                try {
                    if (!contextMap.containsKey(StandardInputs.EVENT)) {
                        contextMap.put(StandardInputs.EVENT, StandardInputs.eventValue(entity, mapper));
                    }
                    if (!contextMap.containsKey(StandardInputs.SUBJECT)) {
                        contextMap.put(StandardInputs.SUBJECT, orchestrator.subjectValueForEvent(entity));
                    }
                } catch (IllegalStateException e) {
                    return Result.failure(e.getMessage());
                }
            }
        } else if (s.usesEvent()) {
            return Result.failure("Scope '" + s.jsonName() + "' needs an eventId");
        }
        Map<String, Object> elContext = s.usesContext() ? contextMap : Map.of();

        try {
            if (s.isBoolean()) {
                boolean b = s.usesEvent() ? evaluator.evaluate(expression, elContext, event)
                        : evaluator.evaluate(expression, elContext);
                return new Result(true, BooleanNode.valueOf(b), null);
            }
            Object value = s.usesEvent() ? evaluator.resolve(expression, elContext, event)
                    : evaluator.resolve(expression, elContext);
            JsonNode node = value == null ? NullNode.getInstance() : mapper.valueToTree(value);
            return new Result(true, node, null);
        } catch (RuntimeException e) {
            return Result.failure(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }
}
