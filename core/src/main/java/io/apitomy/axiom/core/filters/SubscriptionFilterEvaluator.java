package io.apitomy.axiom.core.filters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Evaluates whether a stream event matches a subscription's filter rules.
 * Reuses the include/exclude glob pattern model from {@link EventFilterEvaluator}
 * but operates on the normalized event envelope fields.
 */
@ApplicationScoped
public class SubscriptionFilterEvaluator {

    @Inject
    ObjectMapper objectMapper;

    /**
     * Default constructor for CDI.
     */
    public SubscriptionFilterEvaluator() {
    }

    /**
     * Constructor for unit testing without CDI.
     */
    public SubscriptionFilterEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluates filter rules against a stream event.
     *
     * <p>If filters is null or has empty include/exclude lists, all events pass.
     * Otherwise, include rules are checked first (event must match at least one),
     * then exclude rules (any match blocks the event).
     *
     * @param filters      the include/exclude filter configuration (may be null = allow all)
     * @param eventType    the event's normalized type string (e.g. "issue.created")
     * @param connectionId the event's connection slug
     * @param ref          the event's ref URL
     * @param payload      the event's payload as a JsonNode (for JSON Pointer extraction)
     * @return the filter result (allowed or blocked with matched rule description)
     */
    public FilterResult evaluate(EventSourceFilters filters, String eventType,
                                  String connectionId, String ref, JsonNode payload) {
        if (filters == null) {
            return FilterResult.ALLOWED;
        }

        var includeRules = filters.include() != null ? filters.include() : java.util.List.<EventSourceFilterRule>of();
        var excludeRules = filters.exclude() != null ? filters.exclude() : java.util.List.<EventSourceFilterRule>of();

        // Include rules: event must match at least one (if any exist)
        if (!includeRules.isEmpty()) {
            boolean matched = includeRules.stream()
                    .anyMatch(rule -> ruleMatches(rule, eventType, connectionId, ref, payload));
            if (!matched) {
                return FilterResult.blocked("no include rule matched for event type: " + eventType);
            }
        }

        // Exclude rules: any match blocks the event
        for (EventSourceFilterRule rule : excludeRules) {
            if (ruleMatches(rule, eventType, connectionId, ref, payload)) {
                return FilterResult.blocked(describeRule("exclude", rule));
            }
        }

        return FilterResult.ALLOWED;
    }

    private boolean ruleMatches(EventSourceFilterRule rule, String eventType,
                                 String connectionId, String ref, JsonNode payload) {
        if ("event-type".equals(rule.type())) {
            return EventFilterEvaluator.matchesWildcard(eventType, rule.pattern());
        } else if ("connection".equals(rule.type())) {
            return EventFilterEvaluator.matchesWildcard(connectionId, rule.pattern());
        } else if ("ref".equals(rule.type())) {
            return EventFilterEvaluator.matchesWildcard(ref != null ? ref : "", rule.pattern());
        } else if ("payload".equals(rule.type())) {
            if (payload == null || rule.pointer() == null) {
                return false;
            }
            JsonNode node = payload.at(rule.pointer());
            if (node.isMissingNode() || node.isNull()) {
                return false;
            }
            return EventFilterEvaluator.matchesWildcard(node.asText(), rule.pattern());
        }
        return false;
    }

    private String describeRule(String phase, EventSourceFilterRule rule) {
        if ("event-type".equals(rule.type())) {
            return phase + " event-type " + rule.pattern();
        } else if ("connection".equals(rule.type())) {
            return phase + " connection " + rule.pattern();
        } else if ("ref".equals(rule.type())) {
            return phase + " ref " + rule.pattern();
        }
        return phase + " payload " + rule.pointer() + " matched " + rule.pattern();
    }
}
