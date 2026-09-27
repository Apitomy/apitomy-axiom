package io.apitomy.axiom.core.events.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A user or account that performed an action.
 *
 * @param login       username or account ID (GitHub: user.login, Jira: accountId)
 * @param displayName human-readable display name (nullable)
 * @param avatarUrl   profile image URL (nullable)
 * @param url         profile HTML URL (nullable)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Actor(
        String login,
        String displayName,
        String avatarUrl,
        String url
) {
}
