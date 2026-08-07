package io.apitomy.axiom.core.filters;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Evaluates events against a set of include/exclude filter rules.
 */
@ApplicationScoped
public class EventFilterEvaluator {

    /**
     * Evaluates an event against the given filter configuration.
     *
     * <p>If filters is null or has empty include/exclude lists, all events pass.
     * Otherwise, include rules are checked first (event must match at least one),
     * then exclude rules (any match blocks the event).
     *
     * @param filters   the filter configuration (may be null)
     * @param eventType the event type string (e.g. "issue-created")
     * @param payload   the parsed event payload JSON (may be null)
     * @return the evaluation result
     */
    public FilterResult evaluate(EventSourceFilters filters, String eventType, JsonNode payload) {
        if (filters == null) {
            return FilterResult.ALLOWED;
        }

        List<EventSourceFilterRule> includeRules = filters.include() != null ? filters.include() : List.of();
        List<EventSourceFilterRule> excludeRules = filters.exclude() != null ? filters.exclude() : List.of();

        if (!includeRules.isEmpty()) {
            boolean matched = includeRules.stream()
                    .anyMatch(rule -> ruleMatches(rule, eventType, payload));
            if (!matched) {
                return FilterResult.blocked("no include rule matched for event type: " + eventType);
            }
        }

        for (EventSourceFilterRule rule : excludeRules) {
            if (ruleMatches(rule, eventType, payload)) {
                return FilterResult.blocked(describeRule("exclude", rule));
            }
        }

        return FilterResult.ALLOWED;
    }

    private boolean ruleMatches(EventSourceFilterRule rule, String eventType, JsonNode payload) {
        if ("event-type".equals(rule.type())) {
            return matchesWildcard(eventType, rule.pattern());
        } else if ("payload".equals(rule.type())) {
            if (payload == null || rule.pointer() == null) {
                return false;
            }
            JsonNode node = payload.at(rule.pointer());
            if (node.isMissingNode() || node.isNull()) {
                return false;
            }
            return matchesWildcard(node.asText(), rule.pattern());
        }
        return false;
    }

    private String describeRule(String phase, EventSourceFilterRule rule) {
        if ("event-type".equals(rule.type())) {
            return phase + " event-type " + rule.pattern();
        }
        return phase + " payload " + rule.pointer() + " matched " + rule.pattern();
    }

    static boolean matchesWildcard(String value, String pattern) {
        if (value == null || pattern == null) {
            return false;
        }
        StringBuilder regex = new StringBuilder("^");
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append("$");
        return Pattern.compile(regex.toString()).matcher(value).matches();
    }
}
