package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static io.apitomy.axiom.events.jira.v2.JiraEventNormalizerV2.textOrNull;

/**
 * Resolves Jira field names (as users configure them and as the changelog records them) to field IDs,
 * built from {@code GET /rest/api/3/field}.
 */
public final class JiraFieldCatalog {

    private final Map<String, String> idsByLowerName;

    private JiraFieldCatalog(Map<String, String> idsByLowerName) {
        this.idsByLowerName = idsByLowerName;
    }

    /**
     * Builds the catalog from the response of {@code GET /rest/api/3/field}.
     *
     * @param fields the JSON array of fields
     * @return the catalog (empty when the input is not an array)
     */
    public static JiraFieldCatalog fromFields(JsonNode fields) {
        Map<String, String> ids = new HashMap<>();
        if (fields != null && fields.isArray()) {
            for (JsonNode field : fields) {
                String id = textOrNull(field, "id");
                String name = textOrNull(field, "name");
                if (id != null && name != null) {
                    ids.putIfAbsent(name.toLowerCase(Locale.ROOT), id);
                }
            }
        }
        return new JiraFieldCatalog(Map.copyOf(ids));
    }

    /**
     * Returns the ID of the field with the given name, ignoring case.
     *
     * @param name the field name
     * @return the field ID, or null when unknown
     */
    public String idFor(String name) {
        return name == null ? null : idsByLowerName.get(name.toLowerCase(Locale.ROOT));
    }
}
