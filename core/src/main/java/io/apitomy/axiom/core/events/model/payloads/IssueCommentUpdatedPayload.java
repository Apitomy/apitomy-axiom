package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.NormalizedComment;
import io.apitomy.axiom.core.events.model.NormalizedIssue;

/** Payload for {@code issue.comment.updated} events. */
public record IssueCommentUpdatedPayload(NormalizedIssue issue, NormalizedComment comment) implements EventPayload {
}
