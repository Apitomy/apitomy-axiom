package io.apitomy.axiom.events.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Classifies and wraps raw GitHub API responses into event types and
 * webhook-like payloads. Shared by the poller and the dry-run service.
 */
public class GitHubEventClassifier {

    private GitHubEventClassifier() {
    }

    /**
     * Determines the event type for a GitHub issue based on timestamps.
     *
     * @param issue the issue JSON from the GitHub API
     * @param since the timestamp to compare against
     * @return the event type, or null if the issue is not relevant
     */
    public static String determineIssueEventType(JsonNode issue, Instant since) {
        String state = issue.path("state").asText("");
        Instant createdAt = parseGitHubTimestamp(issue.path("created_at").asText(null));
        Instant updatedAt = parseGitHubTimestamp(issue.path("updated_at").asText(null));
        Instant closedAt = parseGitHubTimestamp(issue.path("closed_at").asText(null));

        if (since == null) {
            return null;
        }

        if (createdAt != null && createdAt.isAfter(since)) {
            return "issue-created";
        }

        if ("closed".equals(state) && closedAt != null && closedAt.isAfter(since)) {
            return "issue-closed";
        }

        if (updatedAt != null && updatedAt.isAfter(since)) {
            if ("open".equals(state) && closedAt != null) {
                return "issue-reopened";
            }
            return "issue-updated";
        }

        return null;
    }

    /**
     * Determines the event type for a GitHub pull request based on timestamps.
     *
     * @param pr the pull request JSON from the GitHub API
     * @param since the timestamp to compare against
     * @return the event type, or null if the PR is not relevant
     */
    public static String determinePrEventType(JsonNode pr, Instant since) {
        String state = pr.path("state").asText("");
        Instant createdAt = parseGitHubTimestamp(pr.path("created_at").asText(null));
        Instant updatedAt = parseGitHubTimestamp(pr.path("updated_at").asText(null));
        Instant closedAt = parseGitHubTimestamp(pr.path("closed_at").asText(null));
        Instant mergedAt = parseGitHubTimestamp(pr.path("merged_at").asText(null));

        if (since == null) {
            return null;
        }

        if (createdAt != null && createdAt.isAfter(since)) {
            return "pr-created";
        }

        if (mergedAt != null && mergedAt.isAfter(since)) {
            return "pr-merged";
        }

        if ("closed".equals(state) && closedAt != null && closedAt.isAfter(since) && mergedAt == null) {
            return "pr-closed";
        }

        if (updatedAt != null && updatedAt.isAfter(since)) {
            if ("open".equals(state) && closedAt != null) {
                return "pr-reopened";
            }
            return "pr-updated";
        }

        return null;
    }

    /**
     * Wraps a raw GitHub issue JSON as a webhook-like payload.
     *
     * @param mapper the ObjectMapper for creating nodes
     * @param issue the issue JSON
     * @param eventType the classified event type
     * @return the wrapped payload
     */
    public static ObjectNode wrapIssue(ObjectMapper mapper, JsonNode issue, String eventType) {
        String action = switch (eventType) {
            case "issue-created" -> "opened";
            case "issue-closed" -> "closed";
            case "issue-reopened" -> "reopened";
            default -> "edited";
        };
        ObjectNode node = mapper.createObjectNode();
        node.put("action", action);
        node.put("polled", true);
        node.set("issue", issue);
        return node;
    }

    /**
     * Wraps a raw GitHub comment JSON as a webhook-like payload.
     *
     * @param mapper the ObjectMapper for creating nodes
     * @param comment the comment JSON
     * @param issueNumber the issue number this comment belongs to
     * @return the wrapped payload
     */
    public static ObjectNode wrapComment(ObjectMapper mapper, JsonNode comment, int issueNumber) {
        ObjectNode node = mapper.createObjectNode();
        node.put("action", "created");
        node.put("polled", true);
        node.putObject("issue").put("number", issueNumber);
        node.set("comment", comment);
        return node;
    }

    /**
     * Wraps a raw GitHub PR JSON as a webhook-like payload.
     *
     * @param mapper the ObjectMapper for creating nodes
     * @param pr the pull request JSON
     * @param eventType the classified event type
     * @return the wrapped payload
     */
    public static ObjectNode wrapPullRequest(ObjectMapper mapper, JsonNode pr, String eventType) {
        String action = switch (eventType) {
            case "pr-created" -> "opened";
            case "pr-closed" -> "closed";
            case "pr-merged" -> "closed";
            case "pr-reopened" -> "reopened";
            default -> "edited";
        };
        ObjectNode node = mapper.createObjectNode();
        node.put("action", action);
        node.put("polled", true);
        node.set("pull_request", pr);
        return node;
    }

    /**
     * Wraps a raw GitHub review comment JSON as a webhook-like payload.
     *
     * @param mapper the ObjectMapper for creating nodes
     * @param comment the review comment JSON
     * @param prNumber the pull request number this comment belongs to
     * @return the wrapped payload
     */
    public static ObjectNode wrapReviewComment(ObjectMapper mapper, JsonNode comment, int prNumber) {
        ObjectNode node = mapper.createObjectNode();
        node.put("action", "created");
        node.put("polled", true);
        node.putObject("pull_request").put("number", prNumber);
        node.set("comment", comment);
        return node;
    }

    /**
     * Parses a GitHub API timestamp string into an Instant.
     *
     * @param timestamp the timestamp string in ISO 8601 format
     * @return the parsed Instant, or null if parsing fails
     */
    public static Instant parseGitHubTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return DateTimeFormatter.ISO_DATE_TIME.parse(timestamp, Instant::from);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
