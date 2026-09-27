package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.Actor;
import io.apitomy.axiom.core.events.model.NormalizedIssue;

/** Payload for {@code issue.assigned} events. */
public record IssueAssignedPayload(NormalizedIssue issue, Actor assignee) implements EventPayload {
}
