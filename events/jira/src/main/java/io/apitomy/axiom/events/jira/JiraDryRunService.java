package io.apitomy.axiom.events.jira;

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
 * Fetches recent events from a Jira project and classifies them
 * for dry-run filter evaluation. Does not ingest or persist anything.
 */
@ApplicationScoped
public class JiraDryRunService {

    private static final Duration LOOKBACK = Duration.ofDays(7);

    @Inject
    JiraApiClient apiClient;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Fetches recent events from the Jira project described by the
     * configuration and classifies them into event types with payloads.
     *
     * @param baseUrl  Jira base URL
     * @param project  Jira project key
     * @param credentials  authentication credentials
     * @return list of classified events
     */
    public List<DryRunEvent> fetchRecentEvents(String baseUrl, String project,
                                                String credentials) {
        Instant since = Instant.now().minus(LOOKBACK);
        List<DryRunEvent> events = new ArrayList<>();

        var result = apiClient.fetchIssuesUpdatedSince(baseUrl, project, since, credentials);
        if (result.data() != null) {
            JsonNode issues = result.data().path("issues");
            if (issues.isArray()) {
                for (JsonNode issue : issues) {
                    JsonNode fields = issue.path("fields");
                    String key = issue.path("key").asText("");
                    String summary = fields.path("summary").asText("");
                    String eventType = JiraEventClassifier.determineEventType(fields, since);
                    if (eventType != null) {
                        ObjectNode payload = JiraEventClassifier.buildIssuePayload(
                                objectMapper, issue, fields, baseUrl, eventType);
                        events.add(new DryRunEvent(eventType, key, summary, payload));
                    }
                    // Check for new comments
                    JsonNode comments = fields.path("comment").path("comments");
                    if (comments.isArray()) {
                        for (JsonNode comment : comments) {
                            Instant commentCreated = JiraEventClassifier.parseJiraTimestamp(
                                    comment.path("created").asText(null));
                            if (commentCreated != null && commentCreated.isAfter(since)) {
                                String author = comment.path("author")
                                        .path("displayName").asText("unknown");
                                ObjectNode payload = JiraEventClassifier.buildCommentPayload(
                                        objectMapper, issue, fields, comment, baseUrl);
                                events.add(new DryRunEvent("comment-added", key,
                                        "Comment by " + author, payload));
                            }
                        }
                    }
                }
            }
        }

        return events;
    }
}
