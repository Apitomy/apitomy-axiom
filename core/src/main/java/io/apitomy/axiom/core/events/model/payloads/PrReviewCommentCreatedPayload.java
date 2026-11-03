package io.apitomy.axiom.core.events.model.payloads;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.apitomy.axiom.core.events.model.NormalizedComment;
import io.apitomy.axiom.core.events.model.NormalizedPullRequest;

/**
 * Payload for {@code pr.review.comment.created} events: an inline comment on a pull request's diff.
 *
 * @param pullRequest the pull request
 * @param comment     the review comment
 * @param path        path of the commented file, relative to the repository root
 * @param line        line of the diff the comment applies to (null for file-level or outdated comments)
 * @param reviewId    ID of the review the comment belongs to (nullable)
 * @param inReplyToId ID of the comment this one replies to (null when it starts a new thread)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PrReviewCommentCreatedPayload(
        NormalizedPullRequest pullRequest,
        NormalizedComment comment,
        String path,
        Integer line,
        String reviewId,
        String inReplyToId
) implements EventPayload {
}
