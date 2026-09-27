package io.apitomy.axiom.core.events.model.payloads;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Marker interface for typed event payloads. Each event type has exactly one
 * payload class. Jackson polymorphic deserialization is keyed on the "type" field.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        // Issue events
        @JsonSubTypes.Type(value = IssueCreatedPayload.class, name = "issue.created"),
        @JsonSubTypes.Type(value = IssueUpdatedPayload.class, name = "issue.updated"),
        @JsonSubTypes.Type(value = IssueClosedPayload.class, name = "issue.closed"),
        @JsonSubTypes.Type(value = IssueReopenedPayload.class, name = "issue.reopened"),
        @JsonSubTypes.Type(value = IssueAssignedPayload.class, name = "issue.assigned"),
        @JsonSubTypes.Type(value = IssueUnassignedPayload.class, name = "issue.unassigned"),
        @JsonSubTypes.Type(value = IssueLabeledPayload.class, name = "issue.labeled"),
        @JsonSubTypes.Type(value = IssueUnlabeledPayload.class, name = "issue.unlabeled"),
        @JsonSubTypes.Type(value = IssueCommentCreatedPayload.class, name = "issue.comment.created"),
        @JsonSubTypes.Type(value = IssueCommentUpdatedPayload.class, name = "issue.comment.updated"),
        @JsonSubTypes.Type(value = IssueCommentDeletedPayload.class, name = "issue.comment.deleted"),
        // PR events
        @JsonSubTypes.Type(value = PrCreatedPayload.class, name = "pr.created"),
        @JsonSubTypes.Type(value = PrClosedPayload.class, name = "pr.closed"),
        @JsonSubTypes.Type(value = PrMergedPayload.class, name = "pr.merged"),
        @JsonSubTypes.Type(value = PrReopenedPayload.class, name = "pr.reopened"),
        @JsonSubTypes.Type(value = PrReviewRequestedPayload.class, name = "pr.review_requested"),
        @JsonSubTypes.Type(value = PrReviewSubmittedPayload.class, name = "pr.review.submitted"),
        @JsonSubTypes.Type(value = PrCommentCreatedPayload.class, name = "pr.comment.created"),
        @JsonSubTypes.Type(value = PrLabeledPayload.class, name = "pr.labeled"),
        @JsonSubTypes.Type(value = PrUnlabeledPayload.class, name = "pr.unlabeled"),
        @JsonSubTypes.Type(value = PrSynchronizePayload.class, name = "pr.synchronize"),
        // Repo events
        @JsonSubTypes.Type(value = PushPayload.class, name = "push"),
        @JsonSubTypes.Type(value = BranchCreatedPayload.class, name = "branch.created"),
        @JsonSubTypes.Type(value = BranchDeletedPayload.class, name = "branch.deleted"),
        @JsonSubTypes.Type(value = TagCreatedPayload.class, name = "tag.created"),
        @JsonSubTypes.Type(value = ReleasePublishedPayload.class, name = "release.published"),
})
public sealed interface EventPayload permits
        IssueCreatedPayload, IssueUpdatedPayload, IssueClosedPayload, IssueReopenedPayload,
        IssueAssignedPayload, IssueUnassignedPayload, IssueLabeledPayload, IssueUnlabeledPayload,
        IssueCommentCreatedPayload, IssueCommentUpdatedPayload, IssueCommentDeletedPayload,
        PrCreatedPayload, PrClosedPayload, PrMergedPayload, PrReopenedPayload,
        PrReviewRequestedPayload, PrReviewSubmittedPayload, PrCommentCreatedPayload,
        PrLabeledPayload, PrUnlabeledPayload, PrSynchronizePayload,
        PushPayload, BranchCreatedPayload, BranchDeletedPayload, TagCreatedPayload,
        ReleasePublishedPayload {
}
