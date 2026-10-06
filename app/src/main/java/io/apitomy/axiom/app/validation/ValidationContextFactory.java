package io.apitomy.axiom.app.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.EventSourceEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.core.entities.ToolsetEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds {@link ValidationContext} instances from the database, optionally extended with names of items
 * pending in a configuration pack or an assistant session directory.
 */
@ApplicationScoped
public class ValidationContextFactory {

    private static final Logger LOG = Logger.getLogger(ValidationContextFactory.class);

    @Inject
    ObjectMapper objectMapper;

    /**
     * Reads the names of all existing referenceable entities from the database.
     *
     * @return a context containing database names only
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public ValidationContext databaseOnly() {
        ManagedContext requestContext = Arc.container() != null ? Arc.container().requestContext() : null;
        boolean activated = requestContext != null && !requestContext.isActive();
        if (activated) {
            requestContext.activate();
        }
        try {
            List<WorkflowDefinitionEntity> workflows = WorkflowDefinitionEntity.listAll();
            return new ValidationContext(
                    collect(ToolDefinitionEntity.<ToolDefinitionEntity>listAll(), t -> t.name),
                    collect(ToolsetEntity.<ToolsetEntity>listAll(), t -> t.name),
                    collect(ActionTypeEntity.<ActionTypeEntity>listAll(), a -> a.name),
                    collect(workflows, w -> w.name),
                    workflows.stream().map(w -> w.id).collect(Collectors.toCollection(HashSet::new)),
                    collect(EventSourceEntity.<EventSourceEntity>listAll(), e -> e.id),
                    collect(SecretEntity.<SecretEntity>listAll(), s -> s.name),
                    actionTypeInputs(ActionTypeEntity.<ActionTypeEntity>listAll()));
        } finally {
            if (activated) {
                requestContext.deactivate();
            }
        }
    }

    /**
     * Database names unioned with the names of items contained in a configuration pack.
     *
     * @param pack the configuration pack JSON
     * @return the combined context
     */
    public ValidationContext forPack(JsonNode pack) {
        ValidationContext db = databaseOnly();
        return new ValidationContext(
                union(db.toolNames(), packNames(pack, "tools", "name")),
                union(db.toolsetNames(), packNames(pack, "toolsets", "name")),
                union(db.actionTypeNames(), packNames(pack, "actionTypes", "name")),
                union(db.workflowDefinitionNames(), packNames(pack, "workflowDefinitions", "name")),
                db.workflowDefinitionIds(),
                union(db.eventSourceIds(), packNames(pack, "eventSources", "id")),
                db.secretNames(),
                overlay(db.actionTypeInputs(), pendingInputs(pack != null ? pack.path("actionTypes") : null)));
    }

    /**
     * Database names unioned with the names of items written to an assistant session directory.
     *
     * @param workDir the session working directory
     * @return the combined context
     */
    public ValidationContext forDirectory(Path workDir) {
        ValidationContext db = databaseOnly();
        return new ValidationContext(
                union(db.toolNames(), directoryNames(workDir, ConfigItemType.TOOL, "name")),
                union(db.toolsetNames(), directoryNames(workDir, ConfigItemType.TOOLSET, "name")),
                union(db.actionTypeNames(), directoryNames(workDir, ConfigItemType.ACTION_TYPE, "name")),
                union(db.workflowDefinitionNames(), directoryNames(workDir, ConfigItemType.WORKFLOW, "name")),
                db.workflowDefinitionIds(),
                union(db.eventSourceIds(), directoryNames(workDir, ConfigItemType.EVENT_SOURCE, "id")),
                db.secretNames(),
                overlay(db.actionTypeInputs(), pendingInputs(directoryItems(workDir, ConfigItemType.ACTION_TYPE))));
    }

    /**
     * Builds declared-input maps (input name to required) for the given action types.
     *
     * @param actionTypes the action type entities
     * @return action type name to (input name to required)
     */
    public static Map<String, Map<String, Boolean>> actionTypeInputs(List<ActionTypeEntity> actionTypes) {
        Map<String, Map<String, Boolean>> result = new HashMap<>();
        for (ActionTypeEntity at : actionTypes) {
            Map<String, Boolean> inputs = new LinkedHashMap<>();
            if (at.inputs != null) {
                at.inputs.forEach(f -> inputs.put(f.name, f.required));
            }
            result.put(at.name, inputs);
        }
        return result;
    }

    private static Map<String, Map<String, Boolean>> overlay(Map<String, Map<String, Boolean>> base,
                                                             Map<String, Map<String, Boolean>> pending) {
        Map<String, Map<String, Boolean>> result = new HashMap<>(base);
        result.putAll(pending);
        return result;
    }

    private static Map<String, Map<String, Boolean>> pendingInputs(Iterable<JsonNode> actionTypes) {
        Map<String, Map<String, Boolean>> result = new HashMap<>();
        if (actionTypes == null) {
            return result;
        }
        for (JsonNode item : actionTypes) {
            String name = item.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            Map<String, Boolean> inputs = new LinkedHashMap<>();
            for (JsonNode input : item.path("inputs")) {
                String inputName = input.path("name").asText("");
                if (!inputName.isBlank()) {
                    inputs.put(inputName, input.path("required").asBoolean(false));
                }
            }
            result.put(name, inputs);
        }
        return result;
    }

    private List<JsonNode> directoryItems(Path workDir, ConfigItemType type) {
        List<JsonNode> items = new ArrayList<>();
        if (workDir == null) {
            return items;
        }
        Path dir = workDir.resolve(type.directory());
        if (!Files.isDirectory(dir)) {
            return items;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".json")).forEach(f -> {
                try {
                    items.add(objectMapper.readTree(f.toFile()));
                } catch (IOException e) {
                    LOG.debugf("Skipping unreadable %s: %s", f, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.warnf("Failed to list %s: %s", dir, e.getMessage());
        }
        return items;
    }

    private static <E> Set<String> collect(List<E> entities, Function<E, String> name) {
        return entities.stream().map(name).collect(Collectors.toCollection(HashSet::new));
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> result = new HashSet<>(a);
        result.addAll(b);
        return result;
    }

    private static Set<String> packNames(JsonNode pack, String arrayField, String nameField) {
        Set<String> names = new HashSet<>();
        if (pack == null) {
            return names;
        }
        for (JsonNode item : pack.path(arrayField)) {
            String name = item.path(nameField).asText("");
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    private Set<String> directoryNames(Path workDir, ConfigItemType type, String nameField) {
        Set<String> names = new HashSet<>();
        if (workDir == null) {
            return names;
        }
        Path dir = workDir.resolve(type.directory());
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".json"))
                    .forEach(f -> names.add(nameOf(f, nameField)));
        } catch (IOException e) {
            LOG.warnf("Failed to list %s: %s", dir, e.getMessage());
        }
        return names;
    }

    private String nameOf(Path file, String nameField) {
        String basename = file.getFileName().toString().replaceFirst("\\.json$", "");
        try {
            String name = objectMapper.readTree(file.toFile()).path(nameField).asText("");
            return name.isBlank() ? basename : name;
        } catch (IOException e) {
            return basename;
        }
    }
}
