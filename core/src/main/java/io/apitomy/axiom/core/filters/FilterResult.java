package io.apitomy.axiom.core.filters;

/**
 * Result of evaluating an event against a filter configuration.
 *
 * @param allowed     true if the event passes the filter
 * @param matchedRule human-readable description of the rule that matched, or null if allowed
 */
public record FilterResult(boolean allowed, String matchedRule) {

    /** An event that passed all filters. */
    public static final FilterResult ALLOWED = new FilterResult(true, null);

    /**
     * Creates a blocked result with a description of the matched rule.
     */
    public static FilterResult blocked(String matchedRule) {
        return new FilterResult(false, matchedRule);
    }
}
