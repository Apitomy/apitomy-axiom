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
import io.apitomy.axiom.core.events.model.payloads.IssueCommentDeletedPayload;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Normalizes raw Jira issue JSON (from JQL search results with {@code expand=changelog}, with the complete
 * changelog and comment list) into {@link NormalizedEvent} records with typed payloads. A single issue can
 * produce multiple events: one for its creation, one or more per changelog history entry, and one per
 * comment created or edited after the {@code since} cutoff. Every event carries the issue state as of that
 * event, rebuilt by {@link JiraIssueHistory}.
 */
@ApplicationScoped
public class JiraEventNormalizerV2 {

    private static final Logger LOG = Logger.getLogger(JiraEventNormalizerV2.class);
    private static final String SOURCE = "jira";
    /** Built-in changelog fields (lower case) that produce {@code issue.updated} with a {@code change}. */
    private static final Set<String> UPDATED_FIELDS = Set.of("summary", "description", "priority",
            "resolution", "duedate", "fix version", "component");

    @Inject
    ObjectMapper objectMapper;

    /** Constructor for CDI. */
    public JiraEventNormalizerV2() {
    }

    /**
     * Constructor for testing (direct injection).
     *
     * @param objectMapper the object mapper used to build {@code sourceData}
     */
    public JiraEventNormalizerV2(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Normalizes an issue with no status-category lookup and no extra fields.
     *
     * @param issue         a single issue JSON object from the Jira search results
     * @param since         only events after this instant are included
     * @param eventSourceId the slug of the EventSource that produced this event
     * @param baseUrl       the Jira instance base URL (e.g., {@code https://myorg.atlassian.net})
     * @return the normalized events (may be empty)
     */
    public List<NormalizedEvent> normalizeIssue(JsonNode issue, Instant since,
                                                String eventSourceId, String baseUrl) {
        return normalizeIssue(issue, since, eventSourceId, baseUrl, JiraNormalizationContext.defaults());
    }

    /**
     * Normalizes a single Jira issue into the events that happened after {@code since}.
     *
     * @param issue         a single issue JSON object (complete changelog and comment list)
     * @param since         only events after this instant are included
     * @param eventSourceId the slug of the EventSource that produced this event
     * @param baseUrl       the Jira instance base URL, without trailing slash
     * @param context       status categories and configured extra fields for this poll
     * @return the normalized events (may be empty)
     */
    public List<NormalizedEvent> normalizeIssue(JsonNode issue, Instant since, String eventSourceId,
                                                String baseUrl, JiraNormalizationContext context) {
        List<NormalizedEvent> events = new ArrayList<>();

        String issueKey = textOrNull(issue, "key");
        JsonNode fields = issue.path("fields");
        if (issueKey == null || fields.isMissingNode()) {
            LOG.debugf("Skipping issue with missing key or fields");
            return events;
        }

        String ref = baseUrl + "/browse/" + issueKey;
        JiraIssueHistory history = JiraIssueHistory.rebuild(issue, context);

        // 1. Creation
        Instant createdAt = parseJiraTimestamp(textOrNull(fields, "created"));
        if (createdAt != null && createdAt.isAfter(since)) {
            JiraIssueState state = history.created();
            events.add(buildEvent(issueKey + "-created", eventSourceId, EventType.ISSUE_CREATED, ref,
                    formatTimestamp(createdAt), mapActor(fields.path("reporter")),
                    new IssueCreatedPayload(toNormalizedIssue(issueKey, fields, state, baseUrl)),
                    buildSourceData(issue, fields, state, context)));
        }

        // 2. Changelog entries
        JsonNode histories = issue.path("changelog").path("histories");
        if (histories.isArray()) {
            for (JsonNode entry : histories) {
                Instant historyCreated = parseJiraTimestamp(textOrNull(entry, "created"));
                JsonNode items = entry.path("items");
                if (historyCreated == null || !historyCreated.isAfter(since) || !items.isArray()) {
                    continue;
                }
                String historyId = textOrNull(entry, "id");
                JiraIssueState state = history.after(historyId);
                ItemContext itemContext = new ItemContext(eventSourceId, ref, formatTimestamp(historyCreated),
                        mapActor(entry.path("author")), toNormalizedIssue(issueKey, fields, state, baseUrl),
                        buildSourceData(issue, fields, state, context));

                List<NormalizedEvent> historyEvents = new ArrayList<>();
                for (JsonNode item : items) {
                    historyEvents.addAll(classifyItem(item, itemContext, context));
                }
                if (historyEvents.size() == 1) {
                    events.add(withSourceEventId(historyEvents.get(0), issueKey + "-" + historyId));
                } else {
                    for (int i = 0; i < historyEvents.size(); i++) {
                        events.add(withSourceEventId(historyEvents.get(i), issueKey + "-" + historyId + "-" + i));
                    }
                }
            }
        }

        // 3. Comments (bodies are always the current text: Jira keeps no comment history)
        JsonNode comments = fields.path("comment").path("comments");
        if (comments.isArray()) {
            for (JsonNode comment : comments) {
                String commentId = textOrNull(comment, "id");
                Instant commentCreated = parseJiraTimestamp(textOrNull(comment, "created"));
                Instant commentUpdated = parseJiraTimestamp(textOrNull(comment, "updated"));
                Actor commentAuthor = mapActor(comment.path("author"));
                NormalizedComment normalizedComment = mapComment(comment, issueKey, baseUrl);

                if (commentCreated != null && commentCreated.isAfter(since)) {
                    JiraIssueState state = history.asOf(commentCreated);
                    events.add(buildEvent(issueKey + "-comment-" + commentId, eventSourceId,
                            EventType.ISSUE_COMMENT_CREATED, ref, formatTimestamp(commentCreated), commentAuthor,
                            new IssueCommentCreatedPayload(toNormalizedIssue(issueKey, fields, state, baseUrl),
                                    normalizedComment),
                            buildSourceData(issue, fields, state, context)));
                }

                if (commentUpdated != null && commentCreated != null
                        && commentUpdated.isAfter(commentCreated)
                        && commentUpdated.isAfter(since)) {
                    JiraIssueState state = history.asOf(commentUpdated);
                    events.add(buildEvent(issueKey + "-comment-" + commentId + "-updated-" + commentUpdated.toEpochMilli(), eventSourceId,
                            EventType.ISSUE_COMMENT_UPDATED, ref, formatTimestamp(commentUpdated), commentAuthor,
                            new IssueCommentUpdatedPayload(toNormalizedIssue(issueKey, fields, state, baseUrl),
                                    normalizedComment),
                            buildSourceData(issue, fields, state, context)));
                }
            }
        }

        return events;
    }

    /** Shared values for the events produced by one changelog history entry. */
    private record ItemContext(String eventSourceId, String ref, String timestamp, Actor actor,
                               NormalizedIssue issue, JsonNode sourceData) {

        NormalizedEvent event(EventType type, EventPayload payload) {
            return buildEvent(null, eventSourceId, type, ref, timestamp, actor, payload, sourceData);
        }
    }

    /**
     * Classifies one changelog item into zero or more events.
     */
    private List<NormalizedEvent> classifyItem(JsonNode item, ItemContext c, JiraNormalizationContext context) {
        String field = textOrNull(item, "field");
        if (field == null) {
            return List.of();
        }
        String from = textOrNull(item, "from");
        String fromString = textOrNull(item, "fromString");
        String to = textOrNull(item, "to");
        String toString = textOrNull(item, "toString");
        List<NormalizedEvent> out = new ArrayList<>();

        switch (field.toLowerCase(Locale.ROOT)) {
            case "status" -> {
                // With an empty status lookup this falls back to name matching, so a custom done status
                // (e.g. "Shipped") yields issue.updated while the snapshot state may already be closed.
                boolean fromDone = context.statusCategories().isDone(from, fromString);
                boolean toDone = context.statusCategories().isDone(to, toString);
                if (toDone && !fromDone) {
                    out.add(c.event(EventType.ISSUE_CLOSED, new IssueClosedPayload(c.issue())));
                } else if (fromDone && !toDone) {
                    out.add(c.event(EventType.ISSUE_REOPENED, new IssueReopenedPayload(c.issue())));
                } else {
                    out.add(c.event(EventType.ISSUE_UPDATED, new IssueUpdatedPayload(c.issue(),
                            new Change(field, fromString, toString, c.actor()))));
                }
            }
            case "assignee" -> {
                if (toString != null && !toString.isEmpty()) {
                    out.add(c.event(EventType.ISSUE_ASSIGNED,
                            new IssueAssignedPayload(c.issue(), new Actor(to, toString, null, null))));
                } else {
                    Actor previous = from != null || fromString != null
                            ? new Actor(from, fromString, null, null) : null;
                    out.add(c.event(EventType.ISSUE_UNASSIGNED, new IssueUnassignedPayload(c.issue(), previous)));
                }
            }
            case "labels" -> {
                Set<String> oldLabels = parseSpaceSeparatedLabels(fromString);
                Set<String> newLabels = parseSpaceSeparatedLabels(toString);
                for (String label : newLabels) {
                    if (!oldLabels.contains(label)) {
                        out.add(c.event(EventType.ISSUE_LABELED, new IssueLabeledPayload(c.issue(),
                                new NormalizedLabel(label, null, null))));
                    }
                }
                for (String label : oldLabels) {
                    if (!newLabels.contains(label)) {
                        out.add(c.event(EventType.ISSUE_UNLABELED, new IssueUnlabeledPayload(c.issue(),
                                new NormalizedLabel(label, null, null))));
                    }
                }
            }
            case "comment" -> {
                if (fromString != null && toString == null) {
                    out.add(c.event(EventType.ISSUE_COMMENT_DELETED, new IssueCommentDeletedPayload(c.issue(),
                            new NormalizedComment(null, fromString, null, null, null, null))));
                }
            }
            default -> {
                if (UPDATED_FIELDS.contains(field.toLowerCase(Locale.ROOT)) || context.isExtraField(field)) {
                    out.add(c.event(EventType.ISSUE_UPDATED, new IssueUpdatedPayload(c.issue(),
                            new Change(field, fromString, toString, c.actor()))));
                } else {
                    LOG.tracef("Ignoring changelog field: %s", field);
                }
            }
        }
        return out;
    }

    // -- Mapping helpers -------------------------------------------------------

    /**
     * Maps an issue snapshot to a {@link NormalizedIssue}. {@code state} is {@code closed} exactly when the
     * snapshot's status category is {@code done}.
     */
    NormalizedIssue toNormalizedIssue(String issueKey, JsonNode fields, JiraIssueState state, String baseUrl) {
        List<Actor> assignees = state.assigneeAccountId == null && state.assigneeDisplayName == null
                ? List.of()
                : List.of(new Actor(state.assigneeAccountId, state.assigneeDisplayName,
                        state.assigneeAvatarUrl, null));
        String openState = JiraStatusCategories.DONE.equals(state.statusCategoryKey) ? "closed" : "open";
        return new NormalizedIssue(
                issueKey,
                state.summary,
                state.description,
                openState,
                state.statusName,
                mapActor(fields.path("reporter")),
                assignees,
                List.copyOf(state.labels),
                null, // milestone
                baseUrl + "/browse/" + issueKey,
                isoTimestamp(textOrNull(fields, "created")),
                state.updatedAt,
                state.closedAt
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
        String url = baseUrl + "/browse/" + issueKey + "?focusedCommentId=" + commentId;
        return new NormalizedComment(
                commentId,
                extractDescription(comment.path("body")),
                mapActor(comment.path("author")),
                url,
                isoTimestamp(textOrNull(comment, "created")),
                isoTimestamp(textOrNull(comment, "updated"))
        );
    }

    /**
     * Builds the selected Jira {@code sourceData} for a snapshot: {@code jiraId}, {@code project{key,name}},
     * {@code issueType{name}}, {@code status{name,categoryKey}}, {@code priority{name}},
     * {@code resolution{name}}, {@code dueDate}, {@code components[{name}]}, {@code fixVersions[{name}]} and,
     * when configured, {@code extraFields{name: value}}.
     */
    JsonNode buildSourceData(JsonNode issue, JsonNode fields, JiraIssueState state,
                             JiraNormalizationContext context) {
        ObjectNode source = objectMapper.createObjectNode();
        source.put("jiraId", textOrNull(issue, "id"));

        JsonNode project = fields.path("project");
        if (project.isObject()) {
            ObjectNode projectNode = source.putObject("project");
            projectNode.put("key", textOrNull(project, "key"));
            projectNode.put("name", textOrNull(project, "name"));
        }
        JsonNode issueType = fields.path("issuetype");
        if (issueType.isObject()) {
            source.putObject("issueType").put("name", textOrNull(issueType, "name"));
        }
        ObjectNode status = source.putObject("status");
        status.put("name", state.statusName);
        status.put("categoryKey", state.statusCategoryKey);
        if (state.priority != null) {
            source.putObject("priority").put("name", state.priority);
        }
        if (state.resolution != null) {
            source.putObject("resolution").put("name", state.resolution);
        }
        if (state.dueDate != null) {
            source.put("dueDate", state.dueDate);
        }
        if (!state.components.isEmpty()) {
            ArrayNode components = source.putArray("components");
            state.components.forEach(name -> components.addObject().put("name", name));
        }
        if (!state.fixVersions.isEmpty()) {
            ArrayNode fixVersions = source.putArray("fixVersions");
            state.fixVersions.forEach(name -> fixVersions.addObject().put("name", name));
        }
        if (!context.extraFields().isEmpty()) {
            ObjectNode extra = source.putObject("extraFields");
            for (Map.Entry<String, String> field : context.extraFields().entrySet()) {
                extra.put(field.getKey(), state.extraFields.get(field.getKey()));
            }
        }
        return source;
    }

    // -- Internal helpers -------------------------------------------------------

    /**
     * Extracts text from a Jira description or comment body. If the value is an ADF object, recursively
     * collects all text nodes. If it is a plain string, returns it directly.
     */
    static String extractDescription(JsonNode descriptionNode) {
        if (descriptionNode == null || descriptionNode.isMissingNode() || descriptionNode.isNull()) {
            return null;
        }
        if (descriptionNode.isTextual()) {
            return descriptionNode.asText();
        }
        if (descriptionNode.isObject()) {
            StringBuilder sb = new StringBuilder();
            extractAdfText(descriptionNode, sb);
            String result = sb.toString().trim();
            return result.isEmpty() ? null : result;
        }
        return descriptionNode.toString();
    }

    private static void extractAdfText(JsonNode node, StringBuilder sb) {
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
     * Parses space-separated label strings from Jira changelog entries.
     */
    static Set<String> parseSpaceSeparatedLabels(String labelString) {
        if (labelString == null || labelString.trim().isEmpty()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(labelString.trim().split("\\s+"))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static NormalizedEvent buildEvent(String sourceEventId, String eventSourceId,
                                              EventType type, String ref, String timestamp,
                                              Actor actor, EventPayload payload, JsonNode sourceData) {
        return new NormalizedEvent(UUID.randomUUID().toString(), sourceEventId, SOURCE, eventSourceId, type, ref,
                timestamp, actor, payload, sourceData);
    }

    private static NormalizedEvent withSourceEventId(NormalizedEvent event, String sourceEventId) {
        return new NormalizedEvent(event.id(), sourceEventId, event.source(), event.eventSourceId(),
                event.type(), event.ref(), event.timestamp(), event.actor(), event.payload(), event.sourceData());
    }

    /**
     * Parses a Jira API timestamp string into an Instant. Handles both ISO-8601 format and Jira's
     * {@code yyyy-MM-dd'T'HH:mm:ss.SSSZ} format.
     */
    static Instant parseJiraTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return null;
        }
        try {
            return DateTimeFormatter.ISO_DATE_TIME.parse(timestamp, Instant::from);
        } catch (Exception e) {
            try {
                return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").parse(timestamp, Instant::from);
            } catch (Exception e2) {
                LOG.tracef("Failed to parse Jira timestamp: %s", timestamp);
                return null;
            }
        }
    }

    /**
     * Converts a Jira timestamp (e.g. {@code 2026-10-21T14:53:07.835+0000}) to ISO-8601 UTC ({@code …Z}).
     *
     * @param jiraTimestamp the Jira timestamp, may be null
     * @return the ISO form, null for null input, or the input unchanged if it cannot be parsed
     */
    static String isoTimestamp(String jiraTimestamp) {
        if (jiraTimestamp == null) {
            return null;
        }
        Instant parsed = parseJiraTimestamp(jiraTimestamp);
        return parsed != null ? parsed.toString() : jiraTimestamp;
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
