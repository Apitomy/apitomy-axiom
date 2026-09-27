package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.NormalizedLabel;
import io.apitomy.axiom.core.events.model.NormalizedPullRequest;

/** Payload for {@code pr.labeled} events. */
public record PrLabeledPayload(NormalizedPullRequest pullRequest, NormalizedLabel label) implements EventPayload {
}
