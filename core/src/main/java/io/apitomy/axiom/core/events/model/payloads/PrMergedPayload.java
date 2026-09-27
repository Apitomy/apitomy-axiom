package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.NormalizedPullRequest;

/** Payload for {@code pr.merged} events. */
public record PrMergedPayload(NormalizedPullRequest pullRequest) implements EventPayload {
}
