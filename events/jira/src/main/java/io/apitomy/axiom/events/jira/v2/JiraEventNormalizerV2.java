package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.events.model.Actor;
import io.apitomy.axiom.core.events.model.Change;
import io.apitomy.axiom.core.events.model.EventType;
import io.apitomy.axiom.core.events.model.NormalizedComment;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.model.NormalizedIssue;
import io.apitomy.axiom.core.events.model.NormalizedLabel;
import io.apitomy.axiom.core.events.model.payloads.EventPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueAssignedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueClosedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueCommentCreatedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueCommentUpdatedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueCreatedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueLabeledPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueReopenedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueUnassignedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueUnlabeledPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueUpdatedPayload;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Normalizes raw Jira issue JSON (from JQL search results with {@code expand=changelog})
 * into {@link NormalizedEvent} records with typed payloads. A single Jira issue can
 * produce multiple events: one per changelog history item (field-level change) and
 * one per comment that was created or updated after the {@code since} cutoff.
 *
 * <p>This is the V2 normalizer designed for the event-sourcing redesign, replacing
 * the V1 {@code JiraEventClassifier} which used raw ObjectNodes.</p>
 */
@ApplicationScoped
public class JiraEventNormalizerV2 {

    private static final Logger LOG = Logger.getLogger(JiraEventNormalizerV2.class);
    private static final String SOURCE = "jira";

    /** Status category keys that Jira considers "done". */
    private static final Set<String> DONE_CATEGORIES = Set.of("done");

    @Inject
    ObjectMapper objectMapper;

    /** Constructor for CDI. */
    public JiraEventNormalizerV2() {
    }

    /** Constructor for testing (direct injection). */
    public JiraEventNormalizerV2(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Normalizes a single Jira issue (from JQL search with {@code expand=changelog})
     * into a list of {@link NormalizedEvent} records -- one per detected change since
     * the given cutoff.
     *
     * @param issue        a single issue JSON object from the Jira search results
     * @param since        only events after this instant are included
     * @param connectionId the slug of the EventSourceConnection that produced this event
     * @param baseUrl      the Jira instance base URL (e.g., {@code https://myorg.atlassian.net})
     * @return a list of normalized events (may be empty if nothing changed since {@code since})
     */
    public List<NormalizedEvent> normalizeIssue(JsonNode issue, Instant since,
                                                 String connectionId, String baseUrl) {
        List<NormalizedEvent> events = new ArrayList<>();

        String issueKey = textOrNull(issue, "key");
        JsonNode fields = issue.path("fields");
        if (issueKey == null || fields.isMissingNode()) {
            LOG.debugf("Skipping issue with missing key or fields");
            return events;
        }

        String ref = baseUrl + "/browse/" + issueKey;
        NormalizedIssue normalizedIssue = mapIssue(issue, fields, baseUrl);
        JsonNode sourceData = buildSourceData(issue, fields);

        // 1. Check for new issue
        Instant createdAt = parseJiraTimestamp(textOrNull(fields, "created"));
        if (createdAt != null && createdAt.isAfter(since)) {
            events.add(buildEvent(
                    issueKey + "-created",
                    connectionId,
                    EventType.ISSUE_CREATED,
                    ref,
                    formatTimestamp(createdAt),
                    mapActor(fields.path("reporter")),
                    new IssueCreatedPayload(normalizedIssue),
                    sourceData
            ));
        }

        // 2. Process changelog entries
        JsonNode histories = issue.path("changelog").path("histories");
        if (histories.isArray()) {
            for (JsonNode history : histories) {
                Instant historyCreated = parseJiraTimestamp(textOrNull(history, "created"));
                if (historyCreated == null || !historyCreated.isAfter(since)) {
                    continue;
                }

                String historyId = textOrNull(history, "id");
                Actor historyActor = mapActor(history.path("author"));
                String historyTimestamp = formatTimestamp(historyCreated);
                JsonNode items = history.path("items");

                if (!items.isArray()) {
                    continue;
                }

                List<NormalizedEvent> historyEvents = new ArrayList<>();

                for (JsonNode item : items) {
                    String field = textOrNull(item, "field");
                    if (field == null) continue;

                    String fromString = textOrNull(item, "fromString");
                    String toString = textOrNull(item, "toString");

                    switch (field) {
                        case "status" -> {
                            Change change = new Change(field, fromString, toString, historyActor);
                            if (isDoneStatus(toString)) {
                                historyEvents.add(buildEvent(
                                        null, connectionId, EventType.ISSUE_CLOSED, ref,
                                        historyTimestamp, historyActor,
                                        new IssueClosedPayload(normalizedIssue), sourceData
                                ));
                            } else if (isDoneStatus(fromString)) {
                                historyEvents.add(buildEvent(
                                        null, connectionId, EventType.ISSUE_REOPENED, ref,
                                        historyTimestamp, historyActor,
                                        new IssueReopenedPayload(normalizedIssue), sourceData
                                ));
                            } else {
                                historyEvents.add(buildEvent(
                                        null, connectionId, EventType.ISSUE_UPDATED, ref,
                                        historyTimestamp, historyActor,
                                        new IssueUpdatedPayload(normalizedIssue, change), sourceData
                                ));
                            }
                        }
                        case "summary", "description" -> {
                            Change change = new Change(field, fromString, toString, historyActor);
                            historyEvents.add(buildEvent(
                                    null, connectionId, EventType.ISSUE_UPDATED, ref,
                                    historyTimestamp, historyActor,
                                    new IssueUpdatedPayload(normalizedIssue, change), sourceData
                            ));
                        }
                        case "assignee" -> {
                            if (toString != null && !toString.isEmpty()) {
                                Actor assignee = new Actor(null, toString, null, null);
                                historyEvents.add(buildEvent(
                                        null, connectionId, EventType.ISSUE_ASSIGNED, ref,
                                        historyTimestamp, historyActor,
                                        new IssueAssignedPayload(normalizedIssue, assignee), sourceData
                                ));
                            } else {
                                Actor previousAssignee = fromString != null
                                        ? new Actor(null, fromString, null, null) : null;
                                historyEvents.add(buildEvent(
                                        null, connectionId, EventType.ISSUE_UNASSIGNED, ref,
                                        historyTimestamp, historyActor,
                                        new IssueUnassignedPayload(normalizedIssue, previousAssignee),
                                        sourceData
                                ));
                            }
                        }
                        case "labels" -> {
                            Set<String> oldLabels = parseSpaceSeparatedLabels(fromString);
                            Set<String> newLabels = parseSpaceSeparatedLabels(toString);

                            // Added labels = in new but not in old
                            for (String label : newLabels) {
                                if (!oldLabels.contains(label)) {
                                    historyEvents.add(buildEvent(
                                            null, connectionId, EventType.ISSUE_LABELED, ref,
                                            historyTimestamp, historyActor,
                                            new IssueLabeledPayload(normalizedIssue,
                                                    new NormalizedLabel(label, null, null)),
                                            sourceData
                                    ));
                                }
                            }
                            // Removed labels = in old but not in new
                            for (String label : oldLabels) {
                                if (!newLabels.contains(label)) {
                                    historyEvents.add(buildEvent(
                                            null, connectionId, EventType.ISSUE_UNLABELED, ref,
                                            historyTimestamp, historyActor,
                                            new IssueUnlabeledPayload(normalizedIssue,
                                                    new NormalizedLabel(label, null, null)),
                                            sourceData
                                    ));
                                }
                            }
                        }
                        default -> LOG.tracef("Ignoring changelog field: %s", field);
                    }
                }

                // Assign sourceEventIds to history events
                if (historyEvents.size() == 1) {
                    NormalizedEvent e = historyEvents.get(0);
                    events.add(withSourceEventId(e, issueKey + "-" + historyId));
                } else {
                    for (int i = 0; i < historyEvents.size(); i++) {
                        NormalizedEvent e = historyEvents.get(i);
                        events.add(withSourceEventId(e, issueKey + "-" + historyId + "-" + i));
                    }
                }
            }
        }

        // 3. Process comments
        JsonNode comments = fields.path("comment").path("comments");
        if (comments.isArray()) {
            for (JsonNode comment : comments) {
                String commentId = textOrNull(comment, "id");
                Instant commentCreated = parseJiraTimestamp(textOrNull(comment, "created"));
                Instant commentUpdated = parseJiraTimestamp(textOrNull(comment, "updated"));
                Actor commentAuthor = mapActor(comment.path("author"));
                NormalizedComment normalizedComment = mapComment(comment, issueKey, baseUrl);

                if (commentCreated != null && commentCreated.isAfter(since)) {
                    events.add(buildEvent(
                            issueKey + "-comment-" + commentId,
                            connectionId,
                            EventType.ISSUE_COMMENT_CREATED,
                            ref,
                            formatTimestamp(commentCreated),
                            commentAuthor,
                            new IssueCommentCreatedPayload(normalizedIssue, normalizedComment),
                            sourceData
                    ));
                }

                if (commentUpdated != null && commentCreated != null
                        && commentUpdated.isAfter(commentCreated)
                        && commentUpdated.isAfter(since)) {
                    events.add(buildEvent(
                            issueKey + "-comment-" + commentId + "-updated",
                            connectionId,
                            EventType.ISSUE_COMMENT_UPDATED,
                            ref,
                            formatTimestamp(commentUpdated),
                            commentAuthor,
                            new IssueCommentUpdatedPayload(normalizedIssue, normalizedComment),
                            sourceData
                    ));
                }
            }
        }

        return events;
    }

    // -- Mapping helpers -------------------------------------------------------

    /**
     * Maps a Jira issue into a {@link NormalizedIssue}.
     */
    NormalizedIssue mapIssue(JsonNode issue, JsonNode fields, String baseUrl) {
        String issueKey = textOrNull(issue, "key");
        String statusCategoryKey = fields.path("status").path("statusCategory")
                .path("key").asText("");
        String state = "done".equals(statusCategoryKey) ? "closed" : "open";
        String stateDetail = textOrNull(fields.path("status"), "name");

        // Description: handle ADF (object) vs plain string
        String description = extractDescription(fields.path("description"));

        // Assignees: wrap single assignee into list
        List<Actor> assignees;
        JsonNode assignee = fields.path("assignee");
        if (!assignee.isMissingNode() && !assignee.isNull()) {
            assignees = List.of(mapActor(assignee));
        } else {
            assignees = Collections.emptyList();
        }

        // Labels
        List<String> labels = new ArrayList<>();
        JsonNode labelsNode = fields.path("labels");
        if (labelsNode.isArray()) {
            for (JsonNode label : labelsNode) {
                labels.add(label.asText());
            }
        }

        return new NormalizedIssue(
                issueKey,
                textOrNull(fields, "summary"),
                description,
                state,
                stateDetail,
                mapActor(fields.path("reporter")),
                assignees,
                labels,
                null, // milestone
                baseUrl + "/browse/" + issueKey,
                textOrNull(fields, "created"),
                textOrNull(fields, "updated"),
                textOrNull(fields, "resolutiondate")
        );
    }

    /**
     * Maps a Jira user JSON node to an {@link Actor}.
     */
    Actor mapActor(JsonNode user) {
        if (user == null || user.isMissingNode() || user.isNull()) {
            return null;
        }
        String login = textOrNull(user, "accountId");
        String displayName = textOrNull(user, "displayName");
        String avatarUrl = textOrNull(user.path("avatarUrls"), "48x48");
        return new Actor(login, displayName, avatarUrl, null);
    }

    /**
     * Maps a Jira comment JSON node to a {@link NormalizedComment}.
     */
    NormalizedComment mapComment(JsonNode comment, String issueKey, String baseUrl) {
        if (comment == null || comment.isMissingNode()) {
            return null;
        }
        String commentId = textOrNull(comment, "id");
        String body = extractDescription(comment.path("body"));
        Actor author = mapActor(comment.path("author"));
        // Jira comments don't have a direct HTML URL in the API response,
        // but we can construct one
        String url = baseUrl + "/browse/" + issueKey
                + "?focusedCommentId=" + commentId;
        return new NormalizedComment(
                commentId,
                body,
                author,
                url,
                textOrNull(comment, "created"),
                textOrNull(comment, "updated")
        );
    }

    // -- Internal helpers -------------------------------------------------------

    /**
     * Extracts text from a Jira description field. If the value is an ADF object,
     * recursively collects all text nodes. If it is a plain string, returns it directly.
     */
    String extractDescription(JsonNode descriptionNode) {
        if (descriptionNode == null || descriptionNode.isMissingNode() || descriptionNode.isNull()) {
            return null;
        }
        if (descriptionNode.isTextual()) {
            return descriptionNode.asText();
        }
        if (descriptionNode.isObject()) {
            // ADF format: recursively extract text nodes
            StringBuilder sb = new StringBuilder();
            extractAdfText(descriptionNode, sb);
            String result = sb.toString().trim();
            return result.isEmpty() ? null : result;
        }
        return descriptionNode.toString();
    }

    private void extractAdfText(JsonNode node, StringBuilder sb) {
        if (node.isTextual()) {
            sb.append(node.asText());
            return;
        }
        if (node.has("text") && node.get("text").isTextual()) {
            sb.append(node.get("text").asText());
        }
        JsonNode content = node.path("content");
        if (content.isArray()) {
            for (JsonNode child : content) {
                extractAdfText(child, sb);
            }
        }
    }

    /**
     * Determines if a status name represents a "done" status.
     * Uses heuristic matching on common done status names since changelog
     * entries only provide status names, not category keys.
     */
    private boolean isDoneStatus(String statusName) {
        if (statusName == null) return false;
        String lower = statusName.toLowerCase();
        return lower.equals("done") || lower.equals("closed") || lower.equals("resolved");
    }

    /**
     * Parses space-separated label strings from Jira changelog entries.
     */
    Set<String> parseSpaceSeparatedLabels(String labelString) {
        if (labelString == null || labelString.trim().isEmpty()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(labelString.trim().split("\\s+"))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private NormalizedEvent buildEvent(String sourceEventId, String connectionId,
                                        EventType type, String ref, String timestamp,
                                        Actor actor, EventPayload payload, JsonNode sourceData) {
        return new NormalizedEvent(
                UUID.randomUUID().toString(),
                sourceEventId,
                SOURCE,
                connectionId,
                type,
                ref,
                timestamp,
                actor,
                payload,
                sourceData
        );
    }

    private NormalizedEvent withSourceEventId(NormalizedEvent event, String sourceEventId) {
        return new NormalizedEvent(
                event.id(),
                sourceEventId,
                event.source(),
                event.connectionId(),
                event.type(),
                event.ref(),
                event.timestamp(),
                event.actor(),
                event.payload(),
                event.sourceData()
        );
    }

    private JsonNode buildSourceData(JsonNode issue, JsonNode fields) {
        ObjectNode source = objectMapper.createObjectNode();
        source.put("jiraId", textOrNull(issue, "id"));

        // Project
        JsonNode project = fields.path("project");
        if (!project.isMissingNode()) {
            ObjectNode projectNode = source.putObject("project");
            projectNode.put("key", textOrNull(project, "key"));
            projectNode.put("name", textOrNull(project, "name"));
        }

        // Issue type
        JsonNode issueType = fields.path("issuetype");
        if (!issueType.isMissingNode()) {
            ObjectNode issueTypeNode = source.putObject("issueType");
            issueTypeNode.put("name", textOrNull(issueType, "name"));
        }

        // Priority
        JsonNode priority = fields.path("priority");
        if (!priority.isMissingNode() && !priority.isNull()) {
            ObjectNode priorityNode = source.putObject("priority");
            priorityNode.put("name", textOrNull(priority, "name"));
        }

        // Resolution
        JsonNode resolution = fields.path("resolution");
        if (!resolution.isMissingNode() && !resolution.isNull()) {
            ObjectNode resolutionNode = source.putObject("resolution");
            resolutionNode.put("name", textOrNull(resolution, "name"));
        }

        // Components
        JsonNode components = fields.path("components");
        if (components.isArray() && components.size() > 0) {
            ArrayNode componentsArray = source.putArray("components");
            for (JsonNode comp : components) {
                ObjectNode compNode = objectMapper.createObjectNode();
                compNode.put("name", textOrNull(comp, "name"));
                componentsArray.add(compNode);
            }
        }

        // Fix versions
        JsonNode fixVersions = fields.path("fixVersions");
        if (fixVersions.isArray() && fixVersions.size() > 0) {
            ArrayNode fixVersionsArray = source.putArray("fixVersions");
            for (JsonNode version : fixVersions) {
                ObjectNode versionNode = objectMapper.createObjectNode();
                versionNode.put("name", textOrNull(version, "name"));
                fixVersionsArray.add(versionNode);
            }
        }

        return source;
    }

    /**
     * Parses a Jira API timestamp string into an Instant. Handles both
     * ISO-8601 format and Jira's {@code yyyy-MM-dd'T'HH:mm:ss.SSSZ} format.
     */
    static Instant parseJiraTimestamp(String timestamp) {
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

    private static String formatTimestamp(Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }
}
