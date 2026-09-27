package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.NormalizedPullRequest;
import io.apitomy.axiom.core.events.model.NormalizedReview;

/** Payload for {@code pr.review.submitted} events. */
public record PrReviewSubmittedPayload(NormalizedPullRequest pullRequest, NormalizedReview review) implements EventPayload {
}
