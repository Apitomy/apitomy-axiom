package io.apitomy.axiom.core.events.model;

/**
 * All normalized event types produced by event source connections.
 */
public enum EventType {
    // Issue events
    ISSUE_CREATED("issue.created"),
    ISSUE_UPDATED("issue.updated"),
    ISSUE_CLOSED("issue.closed"),
    ISSUE_REOPENED("issue.reopened"),
    ISSUE_ASSIGNED("issue.assigned"),
    ISSUE_UNASSIGNED("issue.unassigned"),
    ISSUE_LABELED("issue.labeled"),
    ISSUE_UNLABELED("issue.unlabeled"),
    ISSUE_COMMENT_CREATED("issue.comment.created"),
    ISSUE_COMMENT_UPDATED("issue.comment.updated"),
    ISSUE_COMMENT_DELETED("issue.comment.deleted"),

    // Pull request events
    PR_CREATED("pr.created"),
    PR_CLOSED("pr.closed"),
    PR_MERGED("pr.merged"),
    PR_REOPENED("pr.reopened"),
    PR_REVIEW_REQUESTED("pr.review_requested"),
    PR_REVIEW_SUBMITTED("pr.review.submitted"),
    PR_COMMENT_CREATED("pr.comment.created"),
    PR_LABELED("pr.labeled"),
    PR_UNLABELED("pr.unlabeled"),
    PR_SYNCHRONIZE("pr.synchronize"),

    // Repository events
    PUSH("push"),
    BRANCH_CREATED("branch.created"),
    BRANCH_DELETED("branch.deleted"),
    TAG_CREATED("tag.created"),
    RELEASE_PUBLISHED("release.published");

    private final String value;

    EventType(String value) {
        this.value = value;
    }

    /**
     * The dot-separated event type string used in JSON serialization
     * and event matching (e.g., "issue.created", "pr.merged").
     */
    @com.fasterxml.jackson.annotation.JsonValue
    public String value() {
        return value;
    }

    /**
     * Parse an event type string into the corresponding enum constant.
     *
     * @param value the dot-separated event type string
     * @return the matching EventType
     * @throws IllegalArgumentException if no match is found
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    public static EventType fromString(String value) {
        for (EventType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown event type: " + value);
    }
}
