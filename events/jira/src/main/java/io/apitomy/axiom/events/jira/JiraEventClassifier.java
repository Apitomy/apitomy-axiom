package io.apitomy.axiom.events.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Classifies and wraps raw Jira API responses into event types and
 * normalized payloads. Shared by the poller and the dry-run service.
 */
public class JiraEventClassifier {

    private static final Logger LOG = Logger.getLogger(JiraEventClassifier.class);

    private JiraEventClassifier() {
    }

    /**
     * Determines the event type based on issue field timestamps.
     *
     * @param fields the fields object from a Jira issue
     * @param since the timestamp to compare against
     * @return the event type, or null if the issue is not relevant
     */
    public static String determineEventType(JsonNode fields, Instant since) {
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
     * Builds a normalized issue payload for consistency with other event sources.
     *
     * @param mapper the ObjectMapper for creating nodes
     * @param issue the issue JSON from the Jira API
     * @param fields the fields object from the issue
     * @param baseUrl the Jira base URL
     * @param eventType the classified event type
     * @return the normalized payload
     */
    public static ObjectNode buildIssuePayload(ObjectMapper mapper, JsonNode issue,
                                                JsonNode fields, String baseUrl, String eventType) {
        String issueKey = issue.path("key").asText("");
        String action = switch (eventType) {
            case "issue-created" -> "created";
            case "issue-closed" -> "closed";
            case "issue-reopened" -> "reopened";
            default -> "updated";
        };

        ObjectNode node = mapper.createObjectNode();
        node.put("action", action);
        node.put("polled", true);

        ObjectNode issueNode = node.putObject("issue");
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
     *
     * @param mapper the ObjectMapper for creating nodes
     * @param issue the issue JSON from the Jira API
     * @param fields the fields object from the issue
     * @param comment the comment JSON
     * @param baseUrl the Jira base URL
     * @return the normalized payload
     */
    public static ObjectNode buildCommentPayload(ObjectMapper mapper, JsonNode issue,
                                                  JsonNode fields, JsonNode comment, String baseUrl) {
        String issueKey = issue.path("key").asText("");
        ObjectNode node = mapper.createObjectNode();
        node.put("action", "commented");
        node.put("polled", true);

        ObjectNode issueNode = node.putObject("issue");
        issueNode.put("key", issueKey);
        issueNode.put("summary", fields.path("summary").asText(""));
        issueNode.put("url", baseUrl + "/browse/" + issueKey);

        ObjectNode commentNode = node.putObject("comment");
        JsonNode author = comment.path("author");
        commentNode.put("author", author.path("emailAddress")
                .asText(author.path("displayName").asText("")));
        commentNode.put("body", comment.path("body").asText(""));
        commentNode.put("created", comment.path("created").asText(""));

        return node;
    }

    /**
     * Parses a Jira API timestamp string into an Instant.
     *
     * @param timestamp the timestamp string
     * @return the parsed Instant, or null if parsing fails
     */
    public static Instant parseJiraTimestamp(String timestamp) {
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
}
