package io.apitomy.axiom.events.github;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Normalizes GitHub webhook payloads into internal event types.
 *
 * <p>GitHub sends an X-GitHub-Event header indicating the event type (e.g. "issues",
 * "issue_comment"), and the payload contains an "action" field (e.g. "opened", "closed").
 * This class maps these to the internal Axiom event type vocabulary.</p>
 */
public final class GitHubEventNormalizer {

    private GitHubEventNormalizer() {
    }

    /**
     * Determines the internal event type from the GitHub event header and payload.
     *
     * @param githubEvent the X-GitHub-Event header value
     * @param payload the parsed JSON payload
     * @return the internal event type, or null if the event should be ignored
     */
    public static String normalizeEventType(String githubEvent, JsonNode payload) {
        String action = payload.has("action") ? payload.get("action").asText() : null;

        return switch (githubEvent) {
            case "issues" -> normalizeIssueEvent(action);
            case "issue_comment" -> normalizeIssueCommentEvent(action);
            case "pull_request" -> normalizePullRequestEvent(action, payload);
            case "pull_request_review_comment" -> normalizePullRequestReviewCommentEvent(action);
            default -> null;
        };
    }

    /**
     * Extracts the issue reference (owner/repo#number) from the payload.
     *
     * @param payload the parsed JSON payload
     * @return the issue reference, or null if not found
     */
    public static String extractIssueRef(JsonNode payload) {
        JsonNode issue = payload.path("issue");
        JsonNode repo = payload.path("repository");

        if (issue.isMissingNode() || repo.isMissingNode()) {
            return null;
        }

        String fullName = repo.path("full_name").asText(null);
        int number = issue.path("number").asInt(0);

        if (fullName == null || number == 0) {
            return null;
        }

        return fullName + "#" + number;
    }

    /**
     * Extracts the pull request reference (owner/repo#number) from the payload.
     * Falls back to {@link #extractIssueRef(JsonNode)} for issue payloads.
     *
     * @param payload the parsed JSON payload
     * @return the PR or issue reference, or null if not found
     */
    public static String extractPrRef(JsonNode payload) {
        JsonNode pr = payload.path("pull_request");
        JsonNode repo = payload.path("repository");

        if (pr.isMissingNode() || repo.isMissingNode()) {
            return extractIssueRef(payload);
        }

        String fullName = repo.path("full_name").asText(null);
        int number = pr.path("number").asInt(0);

        if (fullName == null || number == 0) {
            return null;
        }

        return fullName + "#" + number;
    }

    /**
     * Extracts the repository identifier (owner/repo) from the payload.
     *
     * @param payload the parsed JSON payload
     * @return the repository full name, or null if not found
     */
    public static String extractRepository(JsonNode payload) {
        return payload.path("repository").path("full_name").asText(null);
    }

    private static String normalizeIssueEvent(String action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case "opened" -> "issue-created";
            case "edited", "labeled", "unlabeled", "assigned", "unassigned" -> "issue-updated";
            case "closed" -> "issue-closed";
            case "reopened" -> "issue-reopened";
            default -> null;
        };
    }

    private static String normalizeIssueCommentEvent(String action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case "created" -> "comment-added";
            default -> null; // edited/deleted comments are ignored for now
        };
    }

    private static String normalizePullRequestEvent(String action, JsonNode payload) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case "opened" -> "pr-created";
            case "edited", "labeled", "unlabeled", "assigned", "unassigned",
                 "review_requested", "review_request_removed",
                 "ready_for_review", "converted_to_draft",
                 "synchronize" -> "pr-updated";
            case "closed" -> {
                boolean merged = payload.path("pull_request").path("merged").asBoolean(false);
                yield merged ? "pr-merged" : "pr-closed";
            }
            case "reopened" -> "pr-reopened";
            default -> null;
        };
    }

    private static String normalizePullRequestReviewCommentEvent(String action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case "created" -> "pr-review-comment";
            default -> null;
        };
    }
}
