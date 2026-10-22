package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.apitomy.axiom.events.jira.v2.JiraEventNormalizerV2.textOrNull;

/**
 * Maps Jira statuses to their status category key ({@code new}, {@code indeterminate}, {@code done}),
 * built from {@code GET /rest/api/3/status}. Lookups go by status ID first, then by status name; when
 * neither is known (for example because the lookup failed) the well-known done names
 * {@code done}, {@code closed} and {@code resolved} are treated as {@code done}.
 */
public final class JiraStatusCategories {

    /** The category key Jira uses for finished work. */
    public static final String DONE = "done";

    private static final Set<String> DONE_NAMES = Set.of("done", "closed", "resolved");

    private final Map<String, String> byId;
    private final Map<String, String> byName;

    private JiraStatusCategories(Map<String, String> byId, Map<String, String> byName) {
        this.byId = byId;
        this.byName = byName;
    }

    /**
     * Returns a lookup with no statuses, which only applies the done-name fallback.
     *
     * @return an empty lookup
     */
    public static JiraStatusCategories empty() {
        return new JiraStatusCategories(Map.of(), Map.of());
    }

    /**
     * Builds the lookup from the response of {@code GET /rest/api/3/status}.
     *
     * @param statuses the JSON array of statuses
     * @return the lookup (empty when the input is not an array)
     */
    public static JiraStatusCategories fromStatuses(JsonNode statuses) {
        Map<String, String> byId = new HashMap<>();
        Map<String, String> byName = new HashMap<>();
        if (statuses != null && statuses.isArray()) {
            for (JsonNode status : statuses) {
                String key = textOrNull(status.path("statusCategory"), "key");
                if (key == null) {
                    continue;
                }
                String id = textOrNull(status, "id");
                String name = textOrNull(status, "name");
                if (id != null) {
                    byId.put(id, key);
                }
                if (name != null) {
                    byName.putIfAbsent(name.toLowerCase(Locale.ROOT), key);
                }
            }
        }
        return new JiraStatusCategories(Map.copyOf(byId), Map.copyOf(byName));
    }

    /**
     * Returns the category key of a status.
     *
     * @param statusId   the status ID (changelog {@code from}/{@code to}), may be null
     * @param statusName the status name (changelog {@code fromString}/{@code toString}), may be null
     * @return the category key, or null when unknown and not a well-known done name
     */
    public String categoryKey(String statusId, String statusName) {
        if (statusId != null && byId.containsKey(statusId)) {
            return byId.get(statusId);
        }
        if (statusName == null) {
            return null;
        }
        String lower = statusName.toLowerCase(Locale.ROOT);
        if (byName.containsKey(lower)) {
            return byName.get(lower);
        }
        return DONE_NAMES.contains(lower) ? DONE : null;
    }

    /**
     * Returns whether a status belongs to the {@code done} category.
     *
     * @param statusId   the status ID, may be null
     * @param statusName the status name, may be null
     * @return true when the category is {@code done}
     */
    public boolean isDone(String statusId, String statusName) {
        return DONE.equals(categoryKey(statusId, statusName));
    }

    /**
     * Returns whether no statuses were loaded.
     *
     * @return true when only the name fallback applies
     */
    public boolean isEmpty() {
        return byId.isEmpty() && byName.isEmpty();
    }
}
