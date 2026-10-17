package io.apitomy.axiom.core.filters;

/**
 * Result of evaluating a subscription filter expression against an event: the event matched, did not match, or the
 * filter could not be evaluated. An evaluation error is never a match (fail closed), but it is reported
 * distinctly so it is not mistaken for a legitimate non-match.
 *
 * @param outcome      the evaluation outcome
 * @param errorMessage why evaluation failed; null unless {@code outcome} is {@link Outcome#ERROR}
 */
public record SubscriptionFilterResult(Outcome outcome, String errorMessage) {

    /** Possible outcomes of a filter evaluation. */
    public enum Outcome {
        MATCH,
        NO_MATCH,
        ERROR
    }

    private static final SubscriptionFilterResult MATCH = new SubscriptionFilterResult(Outcome.MATCH, null);
    private static final SubscriptionFilterResult NO_MATCH = new SubscriptionFilterResult(Outcome.NO_MATCH, null);

    /**
     * Returns the result for a matching event.
     *
     * @return a {@link Outcome#MATCH} result
     */
    public static SubscriptionFilterResult match() {
        return MATCH;
    }

    /**
     * Returns the result for an event the filter legitimately did not match.
     *
     * @return a {@link Outcome#NO_MATCH} result
     */
    public static SubscriptionFilterResult noMatch() {
        return NO_MATCH;
    }

    /**
     * Returns the result for a filter that could not be evaluated.
     *
     * @param errorMessage why evaluation failed
     * @return an {@link Outcome#ERROR} result
     */
    public static SubscriptionFilterResult error(String errorMessage) {
        return new SubscriptionFilterResult(Outcome.ERROR, errorMessage);
    }

    /**
     * Whether the event matched.
     *
     * @return true only for {@link Outcome#MATCH}
     */
    public boolean matched() {
        return outcome == Outcome.MATCH;
    }

    /**
     * Whether the filter could not be evaluated.
     *
     * @return true only for {@link Outcome#ERROR}
     */
    public boolean isError() {
        return outcome == Outcome.ERROR;
    }
}
