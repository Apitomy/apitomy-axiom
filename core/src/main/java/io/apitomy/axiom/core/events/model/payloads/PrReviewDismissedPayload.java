package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.NormalizedPullRequest;
import io.apitomy.axiom.core.events.model.NormalizedReview;

/** Payload for {@code pr.review.dismissed} events. */
public record PrReviewDismissedPayload(NormalizedPullRequest pullRequest, NormalizedReview review) implements EventPayload {
}
