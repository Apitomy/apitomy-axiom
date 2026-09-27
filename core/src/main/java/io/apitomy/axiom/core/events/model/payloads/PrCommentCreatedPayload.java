package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.NormalizedComment;
import io.apitomy.axiom.core.events.model.NormalizedPullRequest;

/** Payload for {@code pr.comment.created} events. */
public record PrCommentCreatedPayload(NormalizedPullRequest pullRequest, NormalizedComment comment) implements EventPayload {
}
