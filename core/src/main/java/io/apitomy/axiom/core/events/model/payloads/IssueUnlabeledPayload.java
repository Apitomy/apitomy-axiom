package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.NormalizedIssue;
import io.apitomy.axiom.core.events.model.NormalizedLabel;

/** Payload for {@code issue.unlabeled} events. */
public record IssueUnlabeledPayload(NormalizedIssue issue, NormalizedLabel label) implements EventPayload {
}
