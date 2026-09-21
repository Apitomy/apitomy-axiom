package io.apitomy.axiom.core.filters;

import io.apitomy.flow.engine.ConditionEvaluator;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Evaluates a subscription's EL filter expression against a stream event.
 * The expression has access to an {@code event} variable containing the
 * event's envelope fields and typed payload.
 */
@ApplicationScoped
public class SubscriptionFilterEvaluator {

    private static final Logger LOG = Logger.getLogger(SubscriptionFilterEvaluator.class);

    private final ConditionEvaluator conditionEvaluator = new ConditionEvaluator();

    /**
     * Evaluates a filter expression against an event map.
     *
     * @param filterExpression the EL expression (null or blank = match all)
     * @param eventMap         the event data, exposed as the "event" variable
     * @return true if the event matches (expression evaluates to true, or no expression)
     */
    public boolean matches(String filterExpression, Map<String, Object> eventMap) {
        if (filterExpression == null || filterExpression.isBlank()) {
            return true;
        }
        try {
            return conditionEvaluator.evaluate(filterExpression, Map.of(), eventMap);
        } catch (Exception e) {
            LOG.warnf("Filter expression evaluation failed for expression '%s': %s",
                    filterExpression, e.getMessage());
            return false; // fail-closed: if expression is broken, don't match
        }
    }

    /**
     * Validates whether a filter expression is syntactically valid.
     *
     * @param filterExpression the expression to validate
     * @return true if the expression can be parsed
     */
    public boolean isValid(String filterExpression) {
        if (filterExpression == null || filterExpression.isBlank()) {
            return true;
        }
        return conditionEvaluator.isValid(filterExpression);
    }
}
