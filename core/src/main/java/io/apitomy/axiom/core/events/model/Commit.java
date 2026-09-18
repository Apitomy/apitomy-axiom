package io.apitomy.axiom.core.events.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A single commit within a push event.
 *
 * @param sha     full commit SHA
 * @param message commit message
 * @param author  git identity (name + email, not a platform user)
 * @param url     HTML URL to the commit
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Commit(
        String sha,
        String message,
        CommitAuthor author,
        String url
) {

    /**
     * Git commit author identity (distinct from platform Actor).
     *
     * @param name  author name
     * @param email author email
     */
    public record CommitAuthor(String name, String email) {
    }
}
