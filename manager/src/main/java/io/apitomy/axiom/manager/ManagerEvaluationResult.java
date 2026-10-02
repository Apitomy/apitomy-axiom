package io.apitomy.axiom.manager;

import java.util.List;

/**
 * Outcome of a single Manager evaluation. Separates a failed evaluation (the AI call
 * failed or its output could not be parsed) from a successful evaluation that simply
 * produced no decisions.
 *
 * @param decisions     the decisions returned by the Manager (empty on failure)
 * @param failed        true if the evaluation failed
 * @param errorMessage  the failure reason (null when not failed)
 * @param activityLogId ID of the {@code manager-evaluated} / {@code manager-error} activity
 *                      row holding the execution log (nullable)
 */
public record ManagerEvaluationResult(
        List<ManagerDecision> decisions,
        boolean failed,
        String errorMessage,
        Long activityLogId
) {

    /**
     * Compact constructor; normalizes a null decision list to an empty list.
     */
    public ManagerEvaluationResult {
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    /**
     * Creates a successful result.
     *
     * @param decisions     the decisions (may be empty)
     * @param activityLogId the {@code manager-evaluated} activity row ID (nullable)
     * @return the result
     */
    public static ManagerEvaluationResult success(List<ManagerDecision> decisions, Long activityLogId) {
        return new ManagerEvaluationResult(decisions, false, null, activityLogId);
    }

    /**
     * Creates a failed result.
     *
     * @param errorMessage  the failure reason
     * @param activityLogId the {@code manager-error} activity row ID (nullable)
     * @return the result
     */
    public static ManagerEvaluationResult failure(String errorMessage, Long activityLogId) {
        return new ManagerEvaluationResult(List.of(), true, errorMessage, activityLogId);
    }
}
