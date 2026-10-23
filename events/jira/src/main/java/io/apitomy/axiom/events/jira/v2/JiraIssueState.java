package io.apitomy.axiom.events.jira.v2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tracked fields of a Jira issue at one point in time. Used as the mutable working copy while
 * {@link JiraIssueHistory} walks the changelog backwards, and as read-only snapshots afterwards.
 * Timestamps ({@code updatedAt}, {@code closedAt}) are ISO-8601 UTC strings.
 */
final class JiraIssueState {

    String statusId;
    String statusName;
    String statusCategoryKey;
    String summary;
    String description;
    String assigneeAccountId;
    String assigneeDisplayName;
    String assigneeAvatarUrl;
    List<String> labels = new ArrayList<>();
    String resolution;
    String closedAt;
    String priority;
    String dueDate;
    List<String> fixVersions = new ArrayList<>();
    List<String> components = new ArrayList<>();
    Map<String, String> extraFields = new LinkedHashMap<>();
    String updatedAt;

    /**
     * Returns a deep copy (lists and maps are copied).
     *
     * @return the copy
     */
    JiraIssueState copy() {
        JiraIssueState copy = new JiraIssueState();
        copy.statusId = statusId;
        copy.statusName = statusName;
        copy.statusCategoryKey = statusCategoryKey;
        copy.summary = summary;
        copy.description = description;
        copy.assigneeAccountId = assigneeAccountId;
        copy.assigneeDisplayName = assigneeDisplayName;
        copy.assigneeAvatarUrl = assigneeAvatarUrl;
        copy.labels = new ArrayList<>(labels);
        copy.resolution = resolution;
        copy.closedAt = closedAt;
        copy.priority = priority;
        copy.dueDate = dueDate;
        copy.fixVersions = new ArrayList<>(fixVersions);
        copy.components = new ArrayList<>(components);
        copy.extraFields = new LinkedHashMap<>(extraFields);
        copy.updatedAt = updatedAt;
        return copy;
    }
}
