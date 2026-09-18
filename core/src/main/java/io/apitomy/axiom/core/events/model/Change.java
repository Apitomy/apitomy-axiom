package io.apitomy.axiom.core.events.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Describes a specific field change, used by events like issue.updated
 * and status transitions. Particularly important for Jira where events
 * are derived from changelog entries.
 *
 * @param field  which field changed (e.g., "summary", "status", "assignee", "labels")
 * @param from   previous value, human-readable (nullable)
 * @param to     new value, human-readable (nullable)
 * @param author who made the change, if different from the envelope actor (nullable)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Change(
        String field,
        String from,
        String to,
        Actor author
) {
}
