package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.NormalizedPullRequest;

/** Payload for {@code pr.synchronize} events -- new commits pushed to PR head. */
public record PrSynchronizePayload(NormalizedPullRequest pullRequest, String before, String after) implements EventPayload {
}
