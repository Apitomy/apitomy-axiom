package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.apitomy.axiom.events.jira.v2.JiraEventNormalizerV2.isoTimestamp;
import static io.apitomy.axiom.events.jira.v2.JiraEventNormalizerV2.parseJiraTimestamp;
import static io.apitomy.axiom.events.jira.v2.JiraEventNormalizerV2.textOrNull;

/**
 * Rebuilds the state of a Jira issue as of each changelog entry by walking the changelog backwards from the
 * current issue. Limits: fields without changelog history keep their current values; a rebuilt description
 * is the changelog's plain/wiki text while the current one is converted from Atlassian Document Format.
 */
final class JiraIssueHistory {

    /** One changelog entry and the state right after it. */
    private record Entry(Instant at, JsonNode history, JiraIssueState state) {
    }

    private final JiraIssueState created;
    private final List<Entry> timeline;
    private final Map<String, JiraIssueState> byHistoryId;

    private JiraIssueHistory(JiraIssueState created, List<Entry> timeline,
                             Map<String, JiraIssueState> byHistoryId) {
        this.created = created;
        this.timeline = timeline;
        this.byHistoryId = byHistoryId;
    }

    /**
     * Rebuilds all snapshots of an issue.
     *
     * @param issue   the issue JSON (search result shape, with the complete changelog)
     * @param context status categories and configured extra fields
     * @return the rebuilt history
     */
    static JiraIssueHistory rebuild(JsonNode issue, JiraNormalizationContext context) {
        JsonNode fields = issue.path("fields");
        JiraIssueState working = currentState(fields, context);
        List<Entry> timeline = new ArrayList<>();
        for (JsonNode history : newestFirst(issue.path("changelog").path("histories"))) {
            Instant at = parseJiraTimestamp(textOrNull(history, "created"));
            JiraIssueState snapshot = working.copy();
            if (at != null) {
                snapshot.updatedAt = at.toString();
            }
            timeline.add(new Entry(at, history, snapshot));
            undo(history, working, context);
        }
        working.updatedAt = isoTimestamp(textOrNull(fields, "created"));
        Collections.reverse(timeline);
        assignClosedAt(working, timeline, fields);

        Map<String, JiraIssueState> byHistoryId = new HashMap<>();
        for (Entry entry : timeline) {
            String id = textOrNull(entry.history(), "id");
            if (id != null) {
                byHistoryId.put(id, entry.state());
            }
        }
        return new JiraIssueHistory(working, List.copyOf(timeline), byHistoryId);
    }

    /**
     * Returns the state at creation (all changelog entries undone).
     *
     * @return the creation snapshot
     */
    JiraIssueState created() {
        return created;
    }

    /**
     * Returns the current state (after the newest changelog entry).
     *
     * @return the current snapshot
     */
    JiraIssueState current() {
        return timeline.isEmpty() ? created : timeline.get(timeline.size() - 1).state();
    }

    /**
     * Returns the state right after a changelog entry.
     *
     * @param historyId the changelog history ID
     * @return the snapshot, or the current state when the ID is unknown
     */
    JiraIssueState after(String historyId) {
        JiraIssueState state = historyId != null ? byHistoryId.get(historyId) : null;
        return state != null ? state : current();
    }

    /**
     * Returns the snapshot in effect at an instant: the state after the newest entry at or before it, or the
     * creation state when there is none.
     *
     * @param instant the instant
     * @return the snapshot
     */
    JiraIssueState asOf(Instant instant) {
        JiraIssueState result = created;
        for (Entry entry : timeline) {
            if (entry.at() == null) {
                continue;
            }
            if (entry.at().isAfter(instant)) {
                break;
            }
            result = entry.state();
        }
        return result;
    }

    /**
     * Renders a Jira field value as display text: scalars as text (integral numbers without a fraction),
     * objects by {@code value}, {@code name}, {@code displayName} or {@code key}, arrays joined with
     * {@code ", "}.
     *
     * @param value the field value
     * @return the text, or null for missing/null/empty values
     */
    static String displayValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            double number = value.asDouble();
            return number == Math.rint(number) && !Double.isInfinite(number)
                    ? String.valueOf(value.asLong()) : value.asText();
        }
        if (value.isValueNode()) {
            return value.asText();
        }
        if (value.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode element : value) {
                String part = displayValue(element);
                if (part != null) {
                    parts.add(part);
                }
            }
            return parts.isEmpty() ? null : String.join(", ", parts);
        }
        for (String key : List.of("value", "name", "displayName", "key")) {
            if (value.hasNonNull(key)) {
                return value.get(key).asText();
            }
        }
        return value.toString();
    }

    private static JiraIssueState currentState(JsonNode fields, JiraNormalizationContext context) {
        JiraIssueState state = new JiraIssueState();
        JsonNode status = fields.path("status");
        state.statusId = textOrNull(status, "id");
        state.statusName = textOrNull(status, "name");
        String categoryKey = textOrNull(status.path("statusCategory"), "key");
        state.statusCategoryKey = categoryKey != null
                ? categoryKey : context.statusCategories().categoryKey(state.statusId, state.statusName);
        state.summary = textOrNull(fields, "summary");
        state.description = JiraEventNormalizerV2.extractDescription(fields.path("description"));
        JsonNode assignee = fields.path("assignee");
        if (assignee.isObject()) {
            state.assigneeAccountId = textOrNull(assignee, "accountId");
            state.assigneeDisplayName = textOrNull(assignee, "displayName");
            state.assigneeAvatarUrl = textOrNull(assignee.path("avatarUrls"), "48x48");
        }
        JsonNode labels = fields.path("labels");
        if (labels.isArray()) {
            labels.forEach(label -> state.labels.add(label.asText()));
        }
        state.resolution = textOrNull(fields.path("resolution"), "name");
        state.priority = textOrNull(fields.path("priority"), "name");
        state.dueDate = textOrNull(fields, "duedate");
        state.fixVersions = names(fields.path("fixVersions"));
        state.components = names(fields.path("components"));
        for (Map.Entry<String, String> extra : context.extraFields().entrySet()) {
            String fieldId = extra.getValue();
            state.extraFields.put(extra.getKey(), fieldId != null ? displayValue(fields.path(fieldId)) : null);
        }
        state.updatedAt = isoTimestamp(textOrNull(fields, "updated"));
        return state;
    }

    private static List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode element : array) {
                String name = textOrNull(element, "name");
                if (name != null) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static List<JsonNode> newestFirst(JsonNode histories) {
        List<JsonNode> list = new ArrayList<>();
        if (histories.isArray()) {
            histories.forEach(list::add);
        }
        Comparator<JsonNode> oldestFirst = Comparator
                .comparing((JsonNode history) -> {
                    Instant at = parseJiraTimestamp(textOrNull(history, "created"));
                    return at != null ? at : Instant.EPOCH;
                })
                .thenComparingLong(JiraIssueHistory::numericId);
        list.sort(oldestFirst.reversed());
        return list;
    }

    private static long numericId(JsonNode history) {
        try {
            return Long.parseLong(textOrNull(history, "id"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Undoes one changelog entry on the working copy, last item first. */
    private static void undo(JsonNode history, JiraIssueState state, JiraNormalizationContext context) {
        JsonNode items = history.path("items");
        if (!items.isArray()) {
            return;
        }
        for (int i = items.size() - 1; i >= 0; i--) {
            JsonNode item = items.get(i);
            String field = textOrNull(item, "field");
            if (field == null) {
                continue;
            }
            String from = textOrNull(item, "from");
            String fromString = textOrNull(item, "fromString");
            String toString = textOrNull(item, "toString");
            switch (field.toLowerCase(Locale.ROOT)) {
                case "status" -> {
                    state.statusId = from;
                    state.statusName = fromString;
                    state.statusCategoryKey = context.statusCategories().categoryKey(from, fromString);
                }
                case "summary" -> state.summary = fromString;
                case "description" -> state.description = fromString;
                case "assignee" -> {
                    state.assigneeAccountId = from;
                    state.assigneeDisplayName = fromString;
                    state.assigneeAvatarUrl = null;
                }
                case "labels" -> state.labels =
                        new ArrayList<>(JiraEventNormalizerV2.parseSpaceSeparatedLabels(fromString));
                case "resolution" -> state.resolution = fromString;
                case "priority" -> state.priority = fromString;
                case "duedate" -> state.dueDate = from != null ? from : fromString;
                case "fix version" -> undoMultiValue(state.fixVersions, fromString, toString);
                case "component" -> undoMultiValue(state.components, fromString, toString);
                default -> {
                    String extraName = context.extraFieldName(field);
                    if (extraName != null) {
                        state.extraFields.put(extraName, fromString);
                    }
                }
            }
        }
    }

    /** Multi-value fields record one item per added ({@code to}) or removed ({@code from}) value. */
    private static void undoMultiValue(List<String> values, String fromString, String toString) {
        if (toString != null) {
            values.remove(toString);
        }
        if (fromString != null && !values.contains(fromString)) {
            values.add(fromString);
        }
    }

    /** Forward pass computing {@code closedAt} for every snapshot. */
    private static void assignClosedAt(JiraIssueState created, List<Entry> timeline, JsonNode fields) {
        boolean hasResolutionHistory = timeline.stream().anyMatch(entry -> hasResolutionItem(entry.history()));
        if (!hasResolutionHistory) {
            String current = isoTimestamp(textOrNull(fields, "resolutiondate"));
            created.closedAt = created.resolution != null ? current : null;
            for (Entry entry : timeline) {
                entry.state().closedAt = entry.state().resolution != null ? current : null;
            }
            return;
        }
        String lastSetAt = created.resolution != null ? created.updatedAt : null;
        created.closedAt = lastSetAt;
        for (Entry entry : timeline) {
            for (JsonNode item : entry.history().path("items")) {
                if ("resolution".equalsIgnoreCase(textOrNull(item, "field"))) {
                    boolean set = textOrNull(item, "to") != null || textOrNull(item, "toString") != null;
                    lastSetAt = set && entry.at() != null ? entry.at().toString() : null;
                }
            }
            entry.state().closedAt = entry.state().resolution != null ? lastSetAt : null;
        }
    }

    private static boolean hasResolutionItem(JsonNode history) {
        for (JsonNode item : history.path("items")) {
            if ("resolution".equalsIgnoreCase(textOrNull(item, "field"))) {
                return true;
            }
        }
        return false;
    }
}
