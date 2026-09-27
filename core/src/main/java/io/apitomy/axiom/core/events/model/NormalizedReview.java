package io.apitomy.axiom.core.events.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A pull request review. GitHub only — no Jira equivalent.
 *
 * @param id          review ID
 * @param state       review state: "approved", "changes_requested", "commented", "dismissed"
 * @param body        review body text (nullable)
 * @param author      who submitted the review
 * @param url         HTML URL to the review
 * @param submittedAt submission timestamp (ISO-8601)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizedReview(
        String id,
        String state,
        String body,
        Actor author,
        String url,
        String submittedAt
) {
}
