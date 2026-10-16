package io.apitomy.axiom.app;

/**
 * Fired when a branch parks on a receive-event node whose look-back window is not empty. Observed
 * after the parking transaction commits, so stored events that arrived before the branch parked can
 * be offered to the new subscription.
 *
 * @param subscriptionId the new {@link io.apitomy.axiom.core.entities.WorkflowEventSubscriptionEntity}'s id
 */
public record EventLookbackRequested(long subscriptionId) {
}
