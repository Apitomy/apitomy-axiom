package io.apitomy.axiom.core.events.model.payloads;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.apitomy.axiom.core.events.model.Change;
import io.apitomy.axiom.core.events.model.NormalizedIssue;

/** Payload for {@code issue.updated} events. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IssueUpdatedPayload(NormalizedIssue issue, Change change) implements EventPayload {
}
