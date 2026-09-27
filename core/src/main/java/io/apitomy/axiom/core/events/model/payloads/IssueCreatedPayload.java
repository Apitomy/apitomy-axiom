package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.NormalizedIssue;

/** Payload for {@code issue.created} events. */
public record IssueCreatedPayload(NormalizedIssue issue) implements EventPayload {
}
