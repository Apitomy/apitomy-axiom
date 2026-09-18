package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.Actor;
import io.apitomy.axiom.core.events.model.NormalizedPullRequest;

/** Payload for {@code pr.review_requested} events. */
public record PrReviewRequestedPayload(NormalizedPullRequest pullRequest, Actor requestedReviewer) implements EventPayload {
}
