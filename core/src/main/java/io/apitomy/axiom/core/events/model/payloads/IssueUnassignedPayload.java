package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.Actor;
import io.apitomy.axiom.core.events.model.NormalizedIssue;

/** Payload for {@code issue.unassigned} events. */
public record IssueUnassignedPayload(NormalizedIssue issue, Actor assignee) implements EventPayload {
}
