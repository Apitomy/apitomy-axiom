package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.NormalizedIssue;

/** Payload for {@code issue.closed} events. */
public record IssueClosedPayload(NormalizedIssue issue) implements EventPayload {
}
