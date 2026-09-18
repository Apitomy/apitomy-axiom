package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.NormalizedIssue;

/** Payload for {@code issue.reopened} events. */
public record IssueReopenedPayload(NormalizedIssue issue) implements EventPayload {
}
