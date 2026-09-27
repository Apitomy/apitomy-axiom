package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.NormalizedIssue;
import io.apitomy.axiom.core.events.model.NormalizedLabel;

/** Payload for {@code issue.labeled} events. */
public record IssueLabeledPayload(NormalizedIssue issue, NormalizedLabel label) implements EventPayload {
}
