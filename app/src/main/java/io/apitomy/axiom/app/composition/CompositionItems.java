package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.WorkflowTemplates;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.core.events.model.RoutingRule;
import io.apitomy.flow.changeset.ContentRevision;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.StreamSupport;

/**
 * Pure helpers for composition item content: supported types, revisions, blank items, pack conversion and
 * references between items.
 */
public final class CompositionItems {

    /** Item types a composition can hold. */
    public static final Set<ConfigItemType> SUPPORTED = Collections.unmodifiableSet(
            EnumSet.of(ConfigItemType.WORKFLOW, ConfigItemType.ACTION_TYPE, ConfigItemType.TOOL,
                    ConfigItemType.SUBSCRIPTION));

    private static final String TOOL_PREFIX = "mcp__axiom-tools__";
    private static final String SDK_PREFIX = "sdk:";
    private static final String BLANK_SCRIPT = "#!/usr/bin/env bash\nset -euo pipefail\n";
    private static final String BLANK_CODE = "export async function run(inputs, ctx) {\n    return {};\n}\n";

    private CompositionItems() {
    }

    /**
     * Resolves a supported item type from its JSON name.
     *
     * @param jsonName for example {@code action-type}
     * @return the type
     * @throws IllegalArgumentException if the name is unknown or the type is not supported in compositions
     */
    public static ConfigItemType supportedType(String jsonName) {
        ConfigItemType type = ConfigItemType.fromName(jsonName);
        if (!SUPPORTED.contains(type)) {
            throw new IllegalArgumentException("Item type not supported in compositions: " + jsonName);
        }
        return type;
    }

    /**
     * Computes the content revision of an item's JSON, exactly as stored (Flow's content hash).
     *
     * @param content the item content (a JSON object)
     * @return the revision, for example {@code sha256:...}
     */
    public static String revisionOf(JsonNode content) {
        return ContentRevision.of(content);
    }

    /**
     * Returns the trimmed {@code name} field, or null when it is missing or blank.
     *
     * @param content the item content
     * @return the name or null
     */
    public static String nameOf(JsonNode content) {
        String name = content.path("name").asText("").trim();
        return name.isEmpty() ? null : name;
    }

    /**
     * Builds the content of a new, empty item.
     *
     * @param type the item type (must be supported)
     * @param name the item name
     * @param mapper the JSON mapper
     * @return the item content
     */
    public static ObjectNode blank(ConfigItemType type, String name, ObjectMapper mapper) {
        return blank(type, name, null, mapper);
    }

    /**
     * Builds the content of a new, empty item, using the given execution mode for an action type.
     *
     * @param type the item type (must be supported)
     * @param name the item name
     * @param executionMode agent, script or node for an action type (null means node); ignored otherwise
     * @param mapper the JSON mapper
     * @return the item content
     */
    public static ObjectNode blank(ConfigItemType type, String name, String executionMode, ObjectMapper mapper) {
        if (type == ConfigItemType.WORKFLOW) {
            return WorkflowTemplates.emptyWorkflow(name, mapper);
        }
        ObjectNode item = mapper.createObjectNode();
        item.put("name", name);
        item.put("description", "");
        if (type == ConfigItemType.SUBSCRIPTION) {
            item.put("filterExpression", "");
            item.putArray("routing");
            return item;
        }
        if (type == ConfigItemType.ACTION_TYPE) {
            String mode = executionMode == null ? "node" : executionMode;
            item.put("executionMode", mode);
            item.putArray("inputs");
            item.putArray("outputs");
            switch (mode) {
                case "agent" -> item.put("promptTemplate", "");
                case "script" -> item.put("scriptTemplate", BLANK_SCRIPT);
                case "node" -> item.put("codeTemplate", BLANK_CODE);
                default -> throw new IllegalArgumentException("Unsupported execution mode: " + mode);
            }
        } else {
            item.putArray("parameters");
            item.put("scriptTemplate", BLANK_SCRIPT);
        }
        return item;
    }

    /**
     * Converts draft content to the item JSON used in configuration packs and by the validators.
     *
     * @param type the item type
     * @param name the item name (for workflows, the workflow definition name)
     * @param content the draft content
     * @param mapper the JSON mapper
     * @return the pack item
     */
    public static ObjectNode toPackItem(ConfigItemType type, String name, JsonNode content, ObjectMapper mapper) {
        if (type != ConfigItemType.WORKFLOW) {
            return (ObjectNode) content.deepCopy();
        }
        ObjectNode item = mapper.createObjectNode();
        item.put("name", name);
        if (content.hasNonNull("description")) {
            item.put("description", content.path("description").asText());
        }
        item.set("content", content.deepCopy());
        return item;
    }

    /**
     * Returns the configuration-pack array that holds items of a type.
     *
     * @param type the item type (must be supported)
     * @return {@code workflowDefinitions}, {@code actionTypes}, {@code tools} or {@code subscriptions}
     */
    public static String packArray(ConfigItemType type) {
        return switch (type) {
            case WORKFLOW -> "workflowDefinitions";
            case ACTION_TYPE -> "actionTypes";
            case TOOL -> "tools";
            case SUBSCRIPTION -> "subscriptions";
            default -> throw new IllegalArgumentException("Item type not supported in compositions: " + type);
        };
    }

    /**
     * Lists the action types referenced by a workflow's action nodes, excluding {@code sdk:} functions.
     *
     * @param workflow the Flow workflow document
     * @return the names, in first-use order
     */
    public static Set<String> referencedActionTypes(JsonNode workflow) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode node : workflow.path("nodes")) {
            String actionType = node.path("config").path("actionType").asText("").trim();
            if ("action".equals(node.path("type").asText()) && !actionType.isEmpty()
                    && !actionType.startsWith(SDK_PREFIX)) {
                names.add(actionType);
            }
        }
        return names;
    }

    /**
     * Lists the Axiom script tools an action type allows ({@code mcp__axiom-tools__<name>} entries). The
     * {@code allowedTools} field may be a string separated by commas or whitespace, or an array.
     *
     * @param actionType the action type item JSON
     * @return the tool names, in order
     */
    public static Set<String> referencedTools(JsonNode actionType) {
        Set<String> names = new LinkedHashSet<>();
        JsonNode allowed = actionType.path("allowedTools");
        Iterable<String> entries = allowed.isArray()
                ? () -> StreamSupport.stream(allowed.spliterator(), false)
                        .map(JsonNode::asText).iterator()
                : Arrays.asList(allowed.asText("").split("[,\\s]+"));
        for (String entry : entries) {
            String trimmed = entry.trim();
            if (trimmed.startsWith(TOOL_PREFIX) && trimmed.length() > TOOL_PREFIX.length()) {
                names.add(trimmed.substring(TOOL_PREFIX.length()));
            }
        }
        return names;
    }

    /**
     * Returns the workflow names a subscription starts: the {@code workflowDefinitionName} of each
     * {@code create-workflow} routing rule.
     *
     * @param subscription the subscription item content
     * @return the referenced workflow names
     */
    public static Set<String> referencedWorkflows(JsonNode subscription) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode rule : subscription.path("routing")) {
            String name = rule.path("workflowDefinitionName").asText("");
            if (RoutingRule.TYPE_CREATE_WORKFLOW.equals(rule.path("type").asText()) && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }
}
