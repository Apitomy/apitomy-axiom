package io.apitomy.axiom.core.events.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A normalized issue representation, source-agnostic across GitHub and Jira.
 *
 * @param number      issue identifier (GitHub: "123", Jira: "PROJ-123")
 * @param title       issue title (GitHub: title, Jira: summary)
 * @param body        issue description (nullable; GitHub: markdown, Jira: plain text from ADF)
 * @param state       normalized state: "open" or "closed"
 * @param stateDetail source-specific state detail (nullable; GitHub: state_reason, Jira: status name)
 * @param author      who created the issue
 * @param assignees   current assignees (Jira wraps single assignee into array)
 * @param labels      label names
 * @param milestone   milestone or sprint name (nullable)
 * @param url         HTML URL to the issue
 * @param createdAt   creation timestamp (ISO-8601)
 * @param updatedAt   last update timestamp (ISO-8601)
 * @param closedAt    closure timestamp (nullable, ISO-8601)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizedIssue(
        String number,
        String title,
        String body,
        String state,
        String stateDetail,
        Actor author,
        List<Actor> assignees,
        List<String> labels,
        String milestone,
        String url,
        String createdAt,
        String updatedAt,
        String closedAt
) {
}
