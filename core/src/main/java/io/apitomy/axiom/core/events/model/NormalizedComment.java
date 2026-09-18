package io.apitomy.axiom.core.events.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A normalized comment on an issue or pull request.
 *
 * @param id        comment ID
 * @param body      comment body text (Jira: ADF converted to plain text)
 * @param author    who wrote the comment
 * @param url       HTML URL to the comment
 * @param createdAt creation timestamp (ISO-8601)
 * @param updatedAt last edit timestamp (ISO-8601)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizedComment(
        String id,
        String body,
        Actor author,
        String url,
        String createdAt,
        String updatedAt
) {
}
