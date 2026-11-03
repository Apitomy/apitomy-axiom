package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.Actor;
import io.apitomy.axiom.core.events.model.NormalizedPullRequest;

/** Payload for {@code pr.unassigned} events. */
public record PrUnassignedPayload(NormalizedPullRequest pullRequest, Actor assignee) implements EventPayload {
}
