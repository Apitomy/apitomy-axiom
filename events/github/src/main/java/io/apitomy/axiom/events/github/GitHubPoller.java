package io.apitomy.axiom.events.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.EventSourceEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.services.EncryptionService;
import io.apitomy.axiom.events.core.ApiResult;
import io.apitomy.axiom.events.core.EventService;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Polls the GitHub API for new issue, comment, and pull request events on monitored event sources.
 *
 * <p>This provides an alternative to webhooks for environments where inbound
 * connectivity is not available (e.g. local development behind a firewall).</p>
 *
 * <p>For each GitHub event source with {@code enabled = true}, the poller:</p>
 * <ol>
 *   <li>Fetches issues updated since the last poll</li>
 *   <li>Fetches comments created since the last poll</li>
 *   <li>Fetches pull requests updated since the last poll</li>
 *   <li>Fetches pull request review comments since the last poll</li>
 *   <li>Determines the event type for each and ingests new events</li>
 *   <li>Updates the event source's {@code lastPolledAt} timestamp</li>
 * </ol>
 */
@ApplicationScoped
public class GitHubPoller {

    private static final Logger LOG = Logger.getLogger(GitHubPoller.class);

    @Inject
    GitHubApiClient apiClient;

    @Inject
    EventService eventService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EncryptionService encryptionService;

    private static final int DEFAULT_POLL_INTERVAL = 60;
    private static final int TICK_INTERVAL = 10;

    private volatile boolean shuttingDown = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    /**
     * Tick every 10 seconds and check each enabled GitHub event source
     * to see if its per-source poll interval has elapsed since the last poll.
     */
    @Scheduled(every = "${axiom.github.tick-interval:" + TICK_INTERVAL + "s}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        if (shuttingDown) {
            return;
        }
        List<EventSourceEntity> sources = EventSourceEntity
                .list("sourceType = ?1 and enabled = ?2", "github", true);

        if (sources.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        for (EventSourceEntity source : sources) {
            if (isDue(source, now)) {
                pollRepository(source);
            }
        }
    }

    private boolean isDue(EventSourceEntity source, Instant now) {
        if (source.lastPolledAt == null) {
            return true;
        }
        int interval = source.pollInterval != null ? source.pollInterval : DEFAULT_POLL_INTERVAL;
        return now.isAfter(source.lastPolledAt.plusSeconds(interval));
    }

    private void pollRepository(EventSourceEntity source) {
        String token = resolveToken(source);
        Instant pollStartedAt = Instant.now();
        Instant since = source.lastPolledAt;
        List<String> logLines = new ArrayList<>();

        // Parse owner and name from configuration JSON
        String owner;
        String name;
        try {
            JsonNode config = objectMapper.readTree(source.configuration);
            owner = config.path("owner").asText(null);
            name = config.path("name").asText(null);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse configuration for event source %d (%s)", source.id, source.name);
            eventService.recordPollLog(source.id, "error",
                    "Configuration error",
                    "Unable to parse event source configuration JSON.\n"
                            + e.getClass().getSimpleName() + ": " + e.getMessage()
                            + "\nRaw configuration: " + source.configuration, 0);
            return;
        }

        if (owner == null || name == null) {
            LOG.warnf("Event source %d (%s) missing owner or name in configuration", source.id, source.name);
            eventService.recordPollLog(source.id, "error",
                    "Configuration error",
                    "Missing 'owner' or 'name' in event source configuration.\n"
                            + "Current configuration: " + source.configuration, 0);
            return;
        }

        String repo = owner + "/" + name;
        boolean hasToken = token != null && !token.isBlank();
        logLines.add("Polling GitHub repository " + repo);
        logLines.add("Authentication: " + (hasToken ? "token configured" : "no token (public access only)"));

        if (since == null) {
            LOG.infof("First poll for %s — setting baseline to now, skipping historical events", repo);
            updateLastPolledAt(source.id, pollStartedAt);
            logLines.add("First poll — baseline set to now, skipping historical events.");
            logLines.add("Subsequent polls will check for changes since this timestamp.");
            eventService.recordPollLog(source.id, "success",
                    "First poll — baseline set", String.join("\n", logLines), 0);
            return;
        }

        logLines.add("Checking for changes since: " + since);

        ApiResult issuesResult = apiClient.fetchIssuesUpdatedSince(owner, name, since, token);
        logLines.add("\n── Issues API ──");
        logLines.add(issuesResult.formatDetail());
        if (!issuesResult.success()) {
            LOG.warnf("GitHub Issues API failed for %s: %s", repo, issuesResult.errorMessage());
            updateLastPolledAt(source.id, pollStartedAt);
            eventService.recordPollLog(source.id, "error",
                    "Issues API failed: HTTP " + issuesResult.statusCode(),
                    String.join("\n", logLines), 0);
            return;
        }

        ApiResult commentsResult = apiClient.fetchCommentsUpdatedSince(owner, name, since, token);
        logLines.add("\n── Comments API ──");
        logLines.add(commentsResult.formatDetail());
        if (!commentsResult.success()) {
            LOG.warnf("GitHub Comments API failed for %s: %s", repo, commentsResult.errorMessage());
            updateLastPolledAt(source.id, pollStartedAt);
            eventService.recordPollLog(source.id, "error",
                    "Comments API failed: HTTP " + commentsResult.statusCode(),
                    String.join("\n", logLines), 0);
            return;
        }

        ApiResult pullsResult = apiClient.fetchPullsUpdatedSince(owner, name, token);
        logLines.add("\n── Pull Requests API ──");
        logLines.add(pullsResult.formatDetail());
        if (!pullsResult.success()) {
            LOG.warnf("GitHub Pulls API failed for %s: %s", repo, pullsResult.errorMessage());
            logLines.add("Pull requests API failed, continuing with issues and comments only.");
        }

        ApiResult reviewCommentsResult = apiClient.fetchPullReviewCommentsUpdatedSince(owner, name, since, token);
        logLines.add("\n── Review Comments API ──");
        logLines.add(reviewCommentsResult.formatDetail());
        if (!reviewCommentsResult.success()) {
            LOG.warnf("GitHub Review Comments API failed for %s: %s", repo, reviewCommentsResult.errorMessage());
            logLines.add("Review comments API failed, continuing without review comments.");
        }

        logLines.add("\n── Events ──");
        int issueEvents = processIssues(source.id, issuesResult, owner, name, since, logLines);
        int commentEvents = processComments(source.id, commentsResult, owner, name, since, logLines);
        int prEvents = pullsResult.success()
                ? processPullRequests(source.id, pullsResult, owner, name, since, logLines)
                : 0;
        int reviewCommentEvents = reviewCommentsResult.success()
                ? processReviewComments(source.id, reviewCommentsResult, owner, name, since, logLines)
                : 0;
        int eventsIngested = issueEvents + commentEvents + prEvents + reviewCommentEvents;

        updateLastPolledAt(source.id, pollStartedAt);

        long durationMs = java.time.Duration.between(pollStartedAt, Instant.now()).toMillis();
        logLines.add("\nPoll completed in " + durationMs + "ms.");

        String summary = eventsIngested > 0
                ? "Polled " + eventsIngested + " event(s) from " + repo
                : "No new events from " + repo;

        if (eventsIngested > 0) {
            LOG.infof("Ingested %d event(s) from %s", eventsIngested, repo);
        }
        eventService.recordPollLog(source.id, "success", summary,
                String.join("\n", logLines), eventsIngested);
    }

    private int processIssues(Long eventSourceId, ApiResult result, String owner, String name,
                               Instant since, List<String> logLines) {
        if (result.data() == null || !result.data().isArray()) {
            logLines.add("Issues: no data returned");
            return 0;
        }

        int total = result.data().size();
        int count = 0;
        String repoFullName = owner + "/" + name;

        for (JsonNode issue : result.data()) {
            if (issue.has("pull_request")) continue;

            String eventType = determineIssueEventType(issue, since);
            if (eventType == null) continue;

            int number = issue.path("number").asInt(0);
            String issueRef = repoFullName + "#" + number;

            try {
                String payload = objectMapper.writeValueAsString(wrapIssueAsWebhookPayload(
                        issue, repoFullName, eventType));
                eventService.ingestEvent(eventSourceId, "github", eventType, issueRef, repoFullName, payload);
                logLines.add("  " + eventType + ": " + issueRef + " — " + issue.path("title").asText(""));
                count++;
            } catch (Exception e) {
                logLines.add("  Failed to ingest " + issueRef + ": " + e.getMessage());
                LOG.warnf(e, "Failed to ingest issue event for %s", issueRef);
            }
        }
        logLines.add("Issues: " + total + " returned by API, " + count + " event(s) ingested");
        return count;
    }

    private int processComments(Long eventSourceId, ApiResult result, String owner, String name,
                                 Instant since, List<String> logLines) {
        if (result.data() == null || !result.data().isArray()) {
            logLines.add("Comments: no data returned");
            return 0;
        }

        int count = 0;
        String repoFullName = owner + "/" + name;

        for (JsonNode comment : result.data()) {
            String issueUrl = comment.path("issue_url").asText("");
            int issueNumber = extractIssueNumberFromUrl(issueUrl);
            if (issueNumber == 0) {
                continue;
            }

            Instant createdAt = parseGitHubTimestamp(comment.path("created_at").asText(null));
            if (since != null && createdAt != null && !createdAt.isAfter(since)) {
                continue;
            }

            String issueRef = repoFullName + "#" + issueNumber;

            try {
                String payload = objectMapper.writeValueAsString(wrapCommentAsWebhookPayload(
                        comment, repoFullName, issueNumber));
                eventService.ingestEvent(eventSourceId, "github", "comment-added", issueRef, repoFullName, payload);
                String author = comment.path("user").path("login").asText("unknown");
                logLines.add("  comment-added: " + issueRef + " by " + author);
                count++;
            } catch (Exception e) {
                logLines.add("  Failed to ingest comment for " + issueRef + ": " + e.getMessage());
                LOG.warnf(e, "Failed to ingest comment event for %s", issueRef);
            }
        }
        logLines.add("Comments: " + result.data().size() + " returned by API, " + count + " new comment(s) ingested");
        return count;
    }

    private int processPullRequests(Long eventSourceId, ApiResult result, String owner, String name,
                                     Instant since, List<String> logLines) {
        if (result.data() == null || !result.data().isArray()) {
            logLines.add("Pull requests: no data returned");
            return 0;
        }

        int total = result.data().size();
        int count = 0;
        String repoFullName = owner + "/" + name;

        for (JsonNode pr : result.data()) {
            Instant updatedAt = parseGitHubTimestamp(pr.path("updated_at").asText(null));
            if (since != null && updatedAt != null && !updatedAt.isAfter(since)) {
                continue;
            }

            String eventType = determinePrEventType(pr, since);
            if (eventType == null) continue;

            int number = pr.path("number").asInt(0);
            String issueRef = repoFullName + "#" + number;

            try {
                String payload = objectMapper.writeValueAsString(wrapPrAsWebhookPayload(
                        pr, repoFullName, eventType));
                eventService.ingestEvent(eventSourceId, "github", eventType, issueRef, repoFullName, payload);
                logLines.add("  " + eventType + ": " + issueRef + " — " + pr.path("title").asText(""));
                count++;
            } catch (Exception e) {
                logLines.add("  Failed to ingest PR " + issueRef + ": " + e.getMessage());
                LOG.warnf(e, "Failed to ingest PR event for %s", issueRef);
            }
        }
        logLines.add("Pull requests: " + total + " returned by API, " + count + " event(s) ingested");
        return count;
    }

    private int processReviewComments(Long eventSourceId, ApiResult result, String owner, String name,
                                       Instant since, List<String> logLines) {
        if (result.data() == null || !result.data().isArray()) {
            logLines.add("Review comments: no data returned");
            return 0;
        }

        int count = 0;
        String repoFullName = owner + "/" + name;

        for (JsonNode comment : result.data()) {
            String prUrl = comment.path("pull_request_url").asText("");
            int prNumber = extractIssueNumberFromUrl(prUrl);
            if (prNumber == 0) {
                continue;
            }

            Instant createdAt = parseGitHubTimestamp(comment.path("created_at").asText(null));
            if (since != null && createdAt != null && !createdAt.isAfter(since)) {
                continue;
            }

            String issueRef = repoFullName + "#" + prNumber;

            try {
                String payload = objectMapper.writeValueAsString(wrapReviewCommentAsWebhookPayload(
                        comment, repoFullName, prNumber));
                eventService.ingestEvent(eventSourceId, "github", "pr-review-comment", issueRef, repoFullName, payload);
                String author = comment.path("user").path("login").asText("unknown");
                logLines.add("  pr-review-comment: " + issueRef + " by " + author);
                count++;
            } catch (Exception e) {
                logLines.add("  Failed to ingest review comment for " + issueRef + ": " + e.getMessage());
                LOG.warnf(e, "Failed to ingest review comment event for %s", issueRef);
            }
        }
        logLines.add("Review comments: " + result.data().size() + " returned by API, " + count + " new comment(s) ingested");
        return count;
    }

    /**
     * Determines the event type for an issue based on its state and timing.
     */
    private String determineIssueEventType(JsonNode issue, Instant since) {
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
     * Determines the event type for a pull request based on its state and timing.
     */
    String determinePrEventType(JsonNode pr, Instant since) {
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
     * Wraps a GitHub Issues API response into a structure resembling a webhook payload,
     * so the downstream processing is consistent regardless of event source.
     */
    private JsonNode wrapIssueAsWebhookPayload(JsonNode issue, String repoFullName, String eventType) {
        String action = switch (eventType) {
            case "issue-created" -> "opened";
            case "issue-closed" -> "closed";
            case "issue-reopened" -> "reopened";
            default -> "edited";
        };
        var node = objectMapper.createObjectNode();
        node.put("action", action);
        node.put("polled", true);
        node.set("issue", issue);
        return node;
    }

    /**
     * Wraps a GitHub Comments API response into a structure resembling a webhook payload.
     */
    private JsonNode wrapCommentAsWebhookPayload(JsonNode comment, String repoFullName, int issueNumber) {
        var node = objectMapper.createObjectNode();
        node.put("action", "created");
        node.put("polled", true);
        node.putObject("issue").put("number", issueNumber);
        node.set("comment", comment);
        return node;
    }

    /**
     * Wraps a GitHub Pulls API response into a structure resembling a webhook payload,
     * so the downstream processing is consistent regardless of event source.
     */
    private JsonNode wrapPrAsWebhookPayload(JsonNode pr, String repoFullName, String eventType) {
        String action = switch (eventType) {
            case "pr-created" -> "opened";
            case "pr-closed" -> "closed";
            case "pr-merged" -> "closed";
            case "pr-reopened" -> "reopened";
            default -> "edited";
        };
        var node = objectMapper.createObjectNode();
        node.put("action", action);
        node.put("polled", true);
        node.set("pull_request", pr);
        return node;
    }

    /**
     * Wraps a GitHub Pull Review Comments API response into a structure resembling a webhook payload.
     */
    private JsonNode wrapReviewCommentAsWebhookPayload(JsonNode comment, String repoFullName, int prNumber) {
        var node = objectMapper.createObjectNode();
        node.put("action", "created");
        node.put("polled", true);
        node.putObject("pull_request").put("number", prNumber);
        node.set("comment", comment);
        return node;
    }

    Instant parseGitHubTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return null;
        }
        try {
            return DateTimeFormatter.ISO_DATE_TIME.parse(timestamp, Instant::from);
        } catch (Exception e) {
            return null;
        }
    }

    private int extractIssueNumberFromUrl(String issueUrl) {
        if (issueUrl == null || issueUrl.isEmpty()) {
            return 0;
        }
        // Format: https://api.github.com/repos/owner/repo/issues/123
        int lastSlash = issueUrl.lastIndexOf('/');
        if (lastSlash < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(issueUrl.substring(lastSlash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Transactional
    void updateLastPolledAt(Long sourceId, Instant polledAt) {
        EventSourceEntity source = EventSourceEntity.findById(sourceId);
        if (source != null) {
            source.lastPolledAt = polledAt;
        }
    }

    /**
     * Resolves the authentication token for an event source. Checks the
     * per-source secret first, then falls back to the default GitHub secrets.
     */
    private String resolveToken(EventSourceEntity source) {
        // Per-source secret takes priority
        if (source.secretName != null && !source.secretName.isBlank()) {
            SecretEntity secret = SecretEntity.find("name", source.secretName).firstResult();
            if (secret != null) {
                try {
                    return encryptionService.decrypt(secret.encryptedValue);
                } catch (Exception e) {
                    LOG.warnf("Failed to decrypt secret '%s' for event source '%s'",
                            source.secretName, source.name);
                }
            }
        }

        // Fall back to default GitHub secrets
        for (String name : List.of("GH_TOKEN", "GITHUB_TOKEN")) {
            SecretEntity secret = SecretEntity.find("name", name).firstResult();
            if (secret != null) {
                try {
                    return encryptionService.decrypt(secret.encryptedValue);
                } catch (Exception e) {
                    LOG.warnf("Failed to decrypt %s secret", name);
                }
            }
        }

        // Last resort: environment variables
        String token = System.getenv("GH_TOKEN");
        if (token == null || token.isBlank()) token = System.getenv("GITHUB_TOKEN");
        return token;
    }
}
