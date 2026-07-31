package io.apitomy.axiom.events.jira;

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
 * Polls the Jira Cloud REST API for new and updated issues on monitored event sources.
 *
 * <p>For each Jira event source with {@code enabled = true}, the poller:</p>
 * <ol>
 *   <li>Searches for issues updated since the last poll using JQL</li>
 *   <li>Determines the event type for each issue (created, updated, closed)</li>
 *   <li>Extracts new comments from issue responses</li>
 *   <li>Ingests events via {@link EventService}</li>
 *   <li>Updates the event source's {@code lastPolledAt} timestamp</li>
 * </ol>
 */
@ApplicationScoped
public class JiraPoller {

    private static final Logger LOG = Logger.getLogger(JiraPoller.class);

    @Inject
    JiraApiClient apiClient;

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
     * Tick every 10 seconds and check each enabled Jira event source
     * to see if its per-source poll interval has elapsed since the last poll.
     */
    @Scheduled(every = "${axiom.jira.tick-interval:" + TICK_INTERVAL + "s}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        if (shuttingDown) {
            return;
        }
        List<EventSourceEntity> sources = EventSourceEntity
                .list("sourceType = ?1 and enabled = ?2", "jira", true);

        if (sources.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        for (EventSourceEntity source : sources) {
            if (isDue(source, now)) {
                pollProject(source);
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

    private void pollProject(EventSourceEntity source) {
        String credentials = resolveCredentials(source);
        Instant pollStartedAt = Instant.now();
        Instant since = source.lastPolledAt;
        List<String> logLines = new ArrayList<>();

        String baseUrl;
        String project;
        try {
            JsonNode config = objectMapper.readTree(source.configuration);
            baseUrl = config.path("baseUrl").asText(null);
            project = config.path("project").asText(null);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse configuration for event source %d (%s)",
                    source.id, source.name);
            eventService.recordPollLog(source.id, "error",
                    "Configuration error",
                    "Unable to parse event source configuration JSON.\n"
                            + e.getClass().getSimpleName() + ": " + e.getMessage()
                            + "\nRaw configuration: " + source.configuration, 0);
            return;
        }

        if (baseUrl == null || project == null) {
            LOG.warnf("Event source %d (%s) missing baseUrl or project in configuration",
                    source.id, source.name);
            eventService.recordPollLog(source.id, "error",
                    "Configuration error",
                    "Missing 'baseUrl' or 'project' in event source configuration.\n"
                            + "Current configuration: " + source.configuration, 0);
            return;
        }

        boolean hasCreds = credentials != null && !credentials.isBlank();
        logLines.add("Polling Jira project " + project + " at " + baseUrl);
        logLines.add("Authentication: " + (hasCreds ? "credentials configured" : "no credentials"));

        if (since == null) {
            LOG.infof("First poll for Jira project %s — setting baseline, skipping historical events",
                    project);
            updateLastPolledAt(source.id, pollStartedAt);
            logLines.add("First poll — baseline set to now, skipping historical events.");
            logLines.add("Subsequent polls will check for changes since this timestamp.");
            eventService.recordPollLog(source.id, "success",
                    "First poll — baseline set", String.join("\n", logLines), 0);
            return;
        }

        logLines.add("Checking for changes since: " + since);

        ApiResult result = apiClient.fetchIssuesUpdatedSince(baseUrl, project, since, credentials);
        logLines.add("\n── Jira Search API ──");
        logLines.add(result.formatDetail());

        if (!result.success()) {
            LOG.warnf("Jira API failed for %s: %s", project, result.errorMessage());
            updateLastPolledAt(source.id, pollStartedAt);
            eventService.recordPollLog(source.id, "error",
                    "Jira API failed: HTTP " + result.statusCode(),
                    String.join("\n", logLines), 0);
            return;
        }

        JsonNode issues = result.data().path("issues");
        if (!issues.isArray()) {
            logLines.add("Unexpected response: missing 'issues' array in search result");
            updateLastPolledAt(source.id, pollStartedAt);
            eventService.recordPollLog(source.id, "error",
                    "Unexpected Jira API response",
                    String.join("\n", logLines), 0);
            return;
        }

        int issueCount = issues.size();
        logLines.add("Issues returned: " + issueCount);

        logLines.add("\n── Events ──");
        int eventsIngested = 0;

        for (JsonNode issue : issues) {
            String issueKey = issue.path("key").asText(null);
            if (issueKey == null) continue;

            JsonNode fields = issue.path("fields");

            String eventType = determineEventType(fields, since);
            if (eventType != null) {
                try {
                    String payload = objectMapper.writeValueAsString(
                            buildIssuePayload(issueKey, fields, baseUrl, eventType));
                    eventService.ingestEvent(source.id, "jira", eventType,
                            issueKey, project, payload);
                    logLines.add("  " + eventType + ": " + issueKey + " — "
                            + fields.path("summary").asText(""));
                    eventsIngested++;
                } catch (Exception e) {
                    logLines.add("  Failed to ingest " + issueKey + ": " + e.getMessage());
                    LOG.warnf(e, "Failed to ingest Jira issue event for %s", issueKey);
                }
            }

            int comments = ingestNewComments(source.id, issueKey, project,
                    fields, baseUrl, since, logLines);
            eventsIngested += comments;
        }

        updateLastPolledAt(source.id, pollStartedAt);

        long durationMs = java.time.Duration.between(pollStartedAt, Instant.now()).toMillis();
        logLines.add("\nPoll completed in " + durationMs + "ms.");

        String summary = eventsIngested > 0
                ? "Polled " + eventsIngested + " event(s) from " + project
                : "No new events from " + project;

        if (eventsIngested > 0) {
            LOG.infof("Ingested %d event(s) from Jira project %s", eventsIngested, project);
        }
        eventService.recordPollLog(source.id, "success", summary,
                String.join("\n", logLines), eventsIngested);
    }

    /**
     * Determines the event type based on issue field timestamps.
     */
    private String determineEventType(JsonNode fields, Instant since) {
        Instant createdAt = parseJiraTimestamp(fields.path("created").asText(null));
        Instant updatedAt = parseJiraTimestamp(fields.path("updated").asText(null));

        if (createdAt != null && createdAt.isAfter(since)) {
            return "issue-created";
        }

        JsonNode resolution = fields.path("resolution");
        JsonNode status = fields.path("status");
        String statusCategory = status.path("statusCategory").path("key").asText("");

        if ("done".equals(statusCategory) && updatedAt != null && updatedAt.isAfter(since)) {
            return "issue-closed";
        }

        if (updatedAt != null && updatedAt.isAfter(since)) {
            return "issue-updated";
        }

        return null;
    }

    /**
     * Extracts new comments from an issue's comment field and ingests them as events.
     */
    private int ingestNewComments(Long eventSourceId, String issueKey, String project,
                                   JsonNode fields, String baseUrl, Instant since,
                                   List<String> logLines) {
        JsonNode commentNode = fields.path("comment").path("comments");
        if (!commentNode.isArray()) return 0;

        int count = 0;
        for (JsonNode comment : commentNode) {
            Instant createdAt = parseJiraTimestamp(comment.path("created").asText(null));
            if (createdAt == null || !createdAt.isAfter(since)) {
                continue;
            }

            try {
                String payload = objectMapper.writeValueAsString(
                        buildCommentPayload(issueKey, fields, comment, baseUrl));
                eventService.ingestEvent(eventSourceId, "jira", "comment-added",
                        issueKey, project, payload);
                String author = comment.path("author").path("displayName").asText("unknown");
                logLines.add("  comment-added: " + issueKey + " by " + author);
                count++;
            } catch (Exception e) {
                logLines.add("  Failed to ingest comment for " + issueKey + ": " + e.getMessage());
                LOG.warnf(e, "Failed to ingest Jira comment event for %s", issueKey);
            }
        }
        return count;
    }

    /**
     * Builds a normalized issue payload for consistency with other event sources.
     */
    private JsonNode buildIssuePayload(String issueKey, JsonNode fields,
                                        String baseUrl, String eventType) {
        String action = switch (eventType) {
            case "issue-created" -> "created";
            case "issue-closed" -> "closed";
            case "issue-reopened" -> "reopened";
            default -> "updated";
        };

        var node = objectMapper.createObjectNode();
        node.put("action", action);
        node.put("polled", true);

        var issueNode = node.putObject("issue");
        issueNode.put("key", issueKey);
        issueNode.put("summary", fields.path("summary").asText(""));
        issueNode.put("status", fields.path("status").path("name").asText(""));
        issueNode.put("priority", fields.path("priority").path("name").asText(""));
        issueNode.put("created", fields.path("created").asText(""));
        issueNode.put("updated", fields.path("updated").asText(""));
        issueNode.put("url", baseUrl + "/browse/" + issueKey);

        JsonNode assignee = fields.path("assignee");
        if (!assignee.isMissingNode() && !assignee.isNull()) {
            issueNode.put("assignee", assignee.path("emailAddress")
                    .asText(assignee.path("displayName").asText("")));
        }

        JsonNode reporter = fields.path("reporter");
        if (!reporter.isMissingNode() && !reporter.isNull()) {
            issueNode.put("reporter", reporter.path("emailAddress")
                    .asText(reporter.path("displayName").asText("")));
        }

        JsonNode labels = fields.path("labels");
        if (labels.isArray()) {
            var labelsArray = issueNode.putArray("labels");
            for (JsonNode label : labels) {
                labelsArray.add(label.asText());
            }
        }

        JsonNode resolution = fields.path("resolution");
        if (!resolution.isMissingNode() && !resolution.isNull()) {
            issueNode.put("resolution", resolution.path("name").asText(""));
        }

        return node;
    }

    /**
     * Builds a normalized comment payload.
     */
    private JsonNode buildCommentPayload(String issueKey, JsonNode fields,
                                          JsonNode comment, String baseUrl) {
        var node = objectMapper.createObjectNode();
        node.put("action", "commented");
        node.put("polled", true);

        var issueNode = node.putObject("issue");
        issueNode.put("key", issueKey);
        issueNode.put("summary", fields.path("summary").asText(""));
        issueNode.put("url", baseUrl + "/browse/" + issueKey);

        var commentNode = node.putObject("comment");
        JsonNode author = comment.path("author");
        commentNode.put("author", author.path("emailAddress")
                .asText(author.path("displayName").asText("")));
        commentNode.put("body", comment.path("body").asText(""));
        commentNode.put("created", comment.path("created").asText(""));

        return node;
    }

    private Instant parseJiraTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) return null;
        try {
            return DateTimeFormatter.ISO_DATE_TIME.parse(timestamp, Instant::from);
        } catch (Exception e) {
            try {
                return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                        .parse(timestamp, Instant::from);
            } catch (Exception e2) {
                LOG.tracef("Failed to parse Jira timestamp: %s", timestamp);
                return null;
            }
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
     * Resolves the authentication credentials for a Jira event source.
     * The credential should be in "email:api_token" format for Jira Cloud.
     */
    private String resolveCredentials(EventSourceEntity source) {
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

        SecretEntity secret = SecretEntity.find("name", "JIRA_API_TOKEN").firstResult();
        if (secret != null) {
            try {
                return encryptionService.decrypt(secret.encryptedValue);
            } catch (Exception e) {
                LOG.warnf("Failed to decrypt JIRA_API_TOKEN secret");
            }
        }

        return System.getenv("JIRA_API_TOKEN");
    }
}
