package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Builds a configuration pack JSON from an assistant working directory. */
public final class AssistantPackBuilder {

    /** Pack array name for each item type. */
    private static final Map<ConfigItemType, String> ARRAYS = Map.of(
            ConfigItemType.TOOL, "tools",
            ConfigItemType.TOOLSET, "toolsets",
            ConfigItemType.ACTION_TYPE, "actionTypes",
            ConfigItemType.SESSION_TEMPLATE, "sessionTemplates",
            ConfigItemType.WORKFLOW, "workflowDefinitions",
            ConfigItemType.EVENT_SOURCE, "eventSources",
            ConfigItemType.SUBSCRIPTION, "subscriptions");

    private AssistantPackBuilder() {
    }

    /**
     * Reads {@code <dir>/<type.directory()>/*.json} for every {@link ConfigItemType} and returns a pack with
     * arrays tools, toolsets, actionTypes, sessionTemplates, workflowDefinitions, eventSources, subscriptions.
     * Workflow files are included as-is ({name, description?, content}).
     *
     * @param workDir the session working directory
     * @param mapper  the object mapper
     * @return the pack
     * @throws IOException if a file cannot be read or parsed
     */
    public static ObjectNode fromDirectory(Path workDir, ObjectMapper mapper) throws IOException {
        ObjectNode pack = mapper.createObjectNode();
        for (ConfigItemType type : ConfigItemType.values()) {
            ArrayNode array = pack.putArray(ARRAYS.get(type));
            Path dir = workDir.resolve(type.directory());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> stream = Files.list(dir)) {
                files = stream.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList();
            }
            for (Path file : files) {
                JsonNode item = mapper.readTree(file.toFile());
                array.add(item);
            }
        }
        return pack;
    }

    /**
     * Counts the items in a pack built by {@link #fromDirectory}.
     *
     * @param pack the pack
     * @return the total number of items across all item arrays
     */
    public static int itemCount(ObjectNode pack) {
        return ARRAYS.values().stream().mapToInt(name -> pack.path(name).size()).sum();
    }
}
