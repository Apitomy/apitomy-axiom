package io.apitomy.axiom.events.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.events.core.DryRunEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches recent events from a GitHub repository and classifies them
 * for dry-run filter evaluation. Does not ingest or persist anything.
 */
@ApplicationScoped
public class GitHubDryRunService {

    private static final Duration LOOKBACK = Duration.ofDays(7);

    @Inject
    GitHubApiClient apiClient;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Fetches recent events from the GitHub repository described by the
     * configuration and classifies them into event types with payloads.
     *
     * @param owner  repository owner
     * @param name   repository name
     * @param token  authentication token
     * @return list of classified events
     */
    public List<DryRunEvent> fetchRecentEvents(String owner, String name, String token) {
        Instant since = Instant.now().minus(LOOKBACK);
        List<DryRunEvent> events = new ArrayList<>();

        var issueResult = apiClient.fetchIssuesUpdatedSince(owner, name, since, token);
        if (issueResult.data() != null && issueResult.data().isArray()) {
            for (JsonNode issue : issueResult.data()) {
                if (issue.has("pull_request")) continue;
                String eventType = GitHubEventClassifier.determineIssueEventType(issue, since);
                if (eventType != null) {
                    String ref = owner + "/" + name + "#" + issue.path("number").asInt();
                    String summary = issue.path("title").asText("");
                    ObjectNode payload = GitHubEventClassifier.wrapIssue(objectMapper, issue, eventType);
                    events.add(new DryRunEvent(eventType, ref, summary, payload));
                }
            }
        }

        var commentResult = apiClient.fetchCommentsUpdatedSince(owner, name, since, token);
        if (commentResult.data() != null && commentResult.data().isArray()) {
            for (JsonNode comment : commentResult.data()) {
                Instant createdAt = GitHubEventClassifier.parseGitHubTimestamp(
                        comment.path("created_at").asText(null));
                if (createdAt != null && createdAt.isAfter(since)) {
                    String issueUrl = comment.path("issue_url").asText("");
                    int issueNumber = extractIssueNumber(issueUrl);
                    String ref = owner + "/" + name + "#" + issueNumber;
                    String author = comment.path("user").path("login").asText("unknown");
                    ObjectNode payload = GitHubEventClassifier.wrapComment(
                            objectMapper, comment, issueNumber);
                    events.add(new DryRunEvent("comment-added", ref,
                            "Comment by " + author, payload));
                }
            }
        }

        var prResult = apiClient.fetchPullsUpdatedSince(owner, name, token);
        if (prResult.data() != null && prResult.data().isArray()) {
            for (JsonNode pr : prResult.data()) {
                String eventType = GitHubEventClassifier.determinePrEventType(pr, since);
                if (eventType != null) {
                    String ref = owner + "/" + name + "#" + pr.path("number").asInt();
                    String summary = pr.path("title").asText("");
                    ObjectNode payload = GitHubEventClassifier.wrapPullRequest(
                            objectMapper, pr, eventType);
                    events.add(new DryRunEvent(eventType, ref, summary, payload));
                }
            }
        }

        var reviewResult = apiClient.fetchPullReviewCommentsUpdatedSince(
                owner, name, since, token);
        if (reviewResult.data() != null && reviewResult.data().isArray()) {
            for (JsonNode comment : reviewResult.data()) {
                Instant createdAt = GitHubEventClassifier.parseGitHubTimestamp(
                        comment.path("created_at").asText(null));
                if (createdAt != null && createdAt.isAfter(since)) {
                    String prUrl = comment.path("pull_request_url").asText("");
                    int prNumber = extractIssueNumber(prUrl);
                    String ref = owner + "/" + name + "#" + prNumber;
                    String author = comment.path("user").path("login").asText("unknown");
                    ObjectNode payload = GitHubEventClassifier.wrapReviewComment(
                            objectMapper, comment, prNumber);
                    events.add(new DryRunEvent("pr-review-comment", ref,
                            "Review comment by " + author, payload));
                }
            }
        }

        return events;
    }

    private int extractIssueNumber(String url) {
        if (url == null || url.isBlank()) return 0;
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < url.length() - 1) {
            try {
                return Integer.parseInt(url.substring(lastSlash + 1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
