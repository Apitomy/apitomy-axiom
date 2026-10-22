package io.apitomy.axiom.events.jira.v2;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-poll inputs to {@link JiraEventNormalizerV2}: the status-category lookup and the configured
 * {@code extraFields}.
 *
 * @param statusCategories status category lookup (never null)
 * @param extraFields      configured extra field name to Jira field ID, in configured order; the ID is null
 *                         when it could not be looked up (changelog-based events still work, current
 *                         values are unknown)
 */
public record JiraNormalizationContext(JiraStatusCategories statusCategories, Map<String, String> extraFields) {

    /**
     * Normalizes null arguments and makes the map unmodifiable.
     *
     * @param statusCategories status category lookup, null for empty
     * @param extraFields      extra fields, null for none
     */
    public JiraNormalizationContext {
        statusCategories = statusCategories != null ? statusCategories : JiraStatusCategories.empty();
        extraFields = extraFields != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(extraFields)) : Map.of();
    }

    /**
     * Returns a context with no status lookup and no extra fields.
     *
     * @return the default context
     */
    public static JiraNormalizationContext defaults() {
        return new JiraNormalizationContext(JiraStatusCategories.empty(), Map.of());
    }

    /**
     * Returns whether a changelog {@code field} value names a configured extra field (ignoring case).
     *
     * @param changelogField the changelog item's {@code field}
     * @return true when configured
     */
    public boolean isExtraField(String changelogField) {
        return extraFieldName(changelogField) != null;
    }

    /**
     * Returns the configured spelling of an extra field that matches a changelog {@code field} value.
     *
     * @param changelogField the changelog item's {@code field}
     * @return the configured name, or null when not configured
     */
    public String extraFieldName(String changelogField) {
        if (changelogField == null) {
            return null;
        }
        return extraFields.keySet().stream()
                .filter(name -> name.equalsIgnoreCase(changelogField))
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the resolved field IDs of the configured extra fields, for the search request.
     *
     * @return the non-null field IDs in configured order
     */
    public List<String> extraFieldIds() {
        return extraFields.values().stream().filter(Objects::nonNull).toList();
    }
}
