package io.apitomy.axiom.app.validation;

/** Kinds of configuration items, with their JSON names and assistant session directory names. */
public enum ConfigItemType {
    TOOL("tool", "tools"), ACTION_TYPE("action-type", "action-types"), TOOLSET("toolset", "toolsets"),
    SESSION_TEMPLATE("session-template", "session-templates"), WORKFLOW("workflow", "workflows"),
    EVENT_SOURCE("event-source", "event-sources"), SUBSCRIPTION("subscription", "subscriptions");

    private final String jsonName;
    private final String directory;

    ConfigItemType(String jsonName, String directory) {
        this.jsonName = jsonName;
        this.directory = directory;
    }

    /** @return the JSON name, e.g. {@code action-type} */
    public String jsonName() {
        return jsonName;
    }

    /** @return the assistant session directory name, e.g. {@code action-types} */
    public String directory() {
        return directory;
    }

    /**
     * Looks up a type by JSON name or directory name.
     *
     * @param name the JSON name or directory name
     * @return the type
     * @throws IllegalArgumentException if unknown
     */
    public static ConfigItemType fromName(String name) {
        for (ConfigItemType t : values()) {
            if (t.jsonName.equals(name) || t.directory.equals(name)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown item type: " + name);
    }
}
