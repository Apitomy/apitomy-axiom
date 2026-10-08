package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Optional behaviours a session template enables. Code checks capabilities instead of template IDs, so new
 * built-in templates (such as the workbench) can opt in to shared behaviour.
 */
public enum SessionCapability {

    /** File-based configuration items in the working directory, with the items, validate and apply endpoints. */
    CONFIG_ITEMS("config-items"),

    /** Re-validate working-directory items after each tool result and feed problems back to the agent. */
    VALIDATION_FEEDBACK("validation-feedback"),

    /** The session is bound to a workbench composition. */
    COMPOSITION("composition");

    private final String jsonName;

    SessionCapability(String jsonName) {
        this.jsonName = jsonName;
    }

    /**
     * Returns the name used in template JSON and REST payloads.
     *
     * @return the JSON name, for example {@code config-items}
     */
    public String jsonName() {
        return jsonName;
    }

    /**
     * Looks up a capability by its JSON name.
     *
     * @param name the JSON name
     * @return the capability, or empty if the name is unknown
     */
    public static Optional<SessionCapability> fromJsonName(String name) {
        return Arrays.stream(values()).filter(c -> c.jsonName.equals(name)).findFirst();
    }

    /**
     * Parses a JSON array of capability names. Unknown names are ignored, and a missing or non-array node gives
     * an empty set.
     *
     * @param array the JSON node, typically a template's {@code capabilities} field
     * @return an unmodifiable set of capabilities
     */
    public static Set<SessionCapability> parse(JsonNode array) {
        if (array == null || !array.isArray()) {
            return Collections.emptySet();
        }
        EnumSet<SessionCapability> result = EnumSet.noneOf(SessionCapability.class);
        array.forEach(n -> fromJsonName(n.asText()).ifPresent(result::add));
        return Collections.unmodifiableSet(result);
    }
}
