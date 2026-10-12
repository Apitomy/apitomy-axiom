package io.apitomy.axiom.app.expressions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.NullNode;
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
 * </table>
 *
 * <p>{@code event} is {@link WorkflowEventMapper#toEventMap(StreamEventEntity, ObjectMapper)} of the stored
 * stream event. Context scopes given an event ID also see {@code context.event}, unless the context already
 * has {@code event}.</p>
 */
@ApplicationScoped
public class ExpressionService {

    /**
     * An authoring scope for an EL expression.
     */
    public enum Scope {
        EDGE_CONDITION("edge-condition", true, false, true),
        ACTION_INPUT("action-input", true, false, false),
        HUMAN_TASK_TITLE("human-task-title", true, false, false),
        CORRELATION_SUBSCRIPTION_KEY("correlation-subscription-key", true, false, false),
        CORRELATION_EVENT_KEY("correlation-event-key", false, true, false),
        RECEIVE_EVENT_MATCH("receive-event-match", true, true, true),
        RECEIVE_EVENT_OUTPUT("receive-event-output", true, true, false);

        private final String jsonName;
        private final boolean context;
        private final boolean event;
        private final boolean bool;

        Scope(String jsonName, boolean context, boolean event, boolean bool) {
            this.jsonName = jsonName;
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
            event = WorkflowEventMapper.toEventMap(entity, mapper);
        } else if (s.usesEvent()) {
            return Result.failure("Scope '" + s.jsonName() + "' needs an eventId");
        }
        if (event != null && s.usesContext() && !contextMap.containsKey("event")) {
            contextMap.put("event", event);
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
