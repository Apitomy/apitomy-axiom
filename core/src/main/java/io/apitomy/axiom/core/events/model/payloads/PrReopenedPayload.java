package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.NormalizedPullRequest;

/** Payload for {@code pr.reopened} events. */
public record PrReopenedPayload(NormalizedPullRequest pullRequest) implements EventPayload {
}
