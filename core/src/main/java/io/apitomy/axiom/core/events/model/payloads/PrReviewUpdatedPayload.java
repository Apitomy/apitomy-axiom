package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.NormalizedPullRequest;
import io.apitomy.axiom.core.events.model.NormalizedReview;

/** Payload for {@code pr.review.updated} events (a submitted review's body was edited). */
public record PrReviewUpdatedPayload(NormalizedPullRequest pullRequest, NormalizedReview review) implements EventPayload {
}
