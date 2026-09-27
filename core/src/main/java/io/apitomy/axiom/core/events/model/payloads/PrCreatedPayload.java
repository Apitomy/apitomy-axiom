package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.NormalizedPullRequest;

/** Payload for {@code pr.created} events. */
public record PrCreatedPayload(NormalizedPullRequest pullRequest) implements EventPayload {
}
