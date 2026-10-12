package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.ImportExportService;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.app.validation.ConfigProblem;
import io.apitomy.axiom.app.validation.ConfigValidationException;
import io.apitomy.axiom.app.validation.ValidationContext;
import io.apitomy.axiom.app.validation.ConfigItemValidationService;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Publishes selected composition drafts as one configuration pack, in one transaction. The composition stays
 * open afterwards.
 */
@ApplicationScoped
public class CompositionPublisher {

    /**
     * Result of a publish.
     *
     * @param created items created in the database
     * @param updated items updated in the database
     * @param items the published composition items, after bookkeeping
     */
    public record Outcome(int created, int updated, List<CompositionItemEntity> items) {
    }

    @Inject
    CompositionService service;

    @Inject
    CompositionValidator validator;

    @Inject
    ConfigItemValidationService validationService;

    @Inject
    ImportExportService importExportService;

    @Inject
    ObjectMapper mapper;

    /**
     * Publishes the selected drafts.
     *
     * @param compositionId the composition id
     * @param itemIds the items to publish
     * @param force publish even if a published item changed outside the workbench since it was loaded
     * @return the outcome
     */
    @Transactional
    public Outcome publish(long compositionId, List<Long> itemIds, boolean force) {
        CompositionEntity composition = service.get(compositionId);
        if (itemIds == null || itemIds.isEmpty()) {
            throw new IllegalArgumentException("Select at least one item to publish");
        }
        List<CompositionItemEntity> selected = itemIds.stream().distinct()
                .map(id -> service.item(compositionId, id)).toList();

        Map<String, List<ConfigProblem>> blocked = new LinkedHashMap<>();
        checkDependencies(compositionId, selected, blocked);
        ValidationContext context = validator.context(compositionId);
        for (CompositionItemEntity item : selected) {
            List<ConfigProblem> errors = validationService.validate(ConfigItemType.fromName(item.itemType),
                    validator.packItem(item), context).stream().filter(ConfigProblem::isError).toList();
            if (!errors.isEmpty()) {
                blocked.computeIfAbsent(key(item), k -> new ArrayList<>()).addAll(errors);
            }
        }
        if (!blocked.isEmpty()) {
            throw new PublishBlockedException(blocked);
        }

        if (!force) {
            List<String> conflicts = selected.stream()
                    .filter(item -> item.baseItemId != null)
                    .filter(item -> !Objects.equals(item.baseContentRevision,
                            service.currentBaseRevision(ConfigItemType.fromName(item.itemType), item.baseItemId)))
                    .map(CompositionPublisher::key).toList();
            if (!conflicts.isEmpty()) {
                throw new CompositionConflictException(
                        "Changed outside the workbench since loaded: " + String.join(", ", conflicts));
            }
        }

        ObjectNode pack = mapper.createObjectNode();
        pack.putObject("metadata").put("name", "Workbench - " + composition.name);
        for (CompositionItemEntity item : selected) {
            ConfigItemType type = ConfigItemType.fromName(item.itemType);
            pack.withArray(CompositionItems.packArray(type)).add(validator.packItem(item));
        }
        ImportExportService.UpsertResult result;
        try {
            result = importExportService.importOrUpdatePack(pack);
        } catch (ConfigValidationException e) {
            throw new PublishBlockedException(e.problemsByItem());
        }

        Instant now = Instant.now();
        for (CompositionItemEntity item : selected) {
            ConfigItemType type = ConfigItemType.fromName(item.itemType);
            item.baseItemId = publishedId(type, item.name);
            item.baseContentRevision = service.currentBaseRevision(type, item.baseItemId);
            item.publishedRevision = item.contentRevision;
            item.publishedOn = now;
        }
        int created = result.workflowDefinitionsCreated() + result.actionTypesCreated() + result.toolsCreated();
        int updated = result.workflowDefinitionsUpdated() + result.actionTypesUpdated() + result.toolsUpdated();
        return new Outcome(created, updated, selected);
    }

    private void checkDependencies(long compositionId, List<CompositionItemEntity> selected,
                                   Map<String, List<ConfigProblem>> blocked) {
        Set<String> selectedKeys = selected.stream().map(CompositionPublisher::key).collect(Collectors.toSet());
        Set<String> draftKeys = service.items(compositionId).stream().map(CompositionPublisher::key)
                .collect(Collectors.toSet());
        for (CompositionItemEntity item : selected) {
            JsonNode content = service.contentOf(item);
            if (ConfigItemType.WORKFLOW.jsonName().equals(item.itemType)) {
                for (String name : CompositionItems.referencedActionTypes(content)) {
                    requireDependency(item, ConfigItemType.ACTION_TYPE, name,
                            ActionTypeEntity.count("name", name) > 0, selectedKeys, draftKeys, blocked);
                }
            } else if (ConfigItemType.ACTION_TYPE.jsonName().equals(item.itemType)) {
                for (String name : CompositionItems.referencedTools(content)) {
                    requireDependency(item, ConfigItemType.TOOL, name,
                            ToolDefinitionEntity.count("name", name) > 0, selectedKeys, draftKeys, blocked);
                }
            }
        }
    }

    private static void requireDependency(CompositionItemEntity item, ConfigItemType type, String name,
                                          boolean inDatabase, Set<String> selectedKeys, Set<String> draftKeys,
                                          Map<String, List<ConfigProblem>> blocked) {
        String dependency = type.jsonName() + "/" + name;
        if (selectedKeys.contains(dependency) || inDatabase) {
            return;
        }
        String message = draftKeys.contains(dependency)
                ? "Uses the draft " + type.jsonName() + " '" + name + "', which must be published with it"
                : "Uses the " + type.jsonName() + " '" + name + "', which does not exist";
        blocked.computeIfAbsent(key(item), k -> new ArrayList<>())
                .add(ConfigProblem.error("", "missing-dependency", message));
    }

    private static Long publishedId(ConfigItemType type, String name) {
        return switch (type) {
            case WORKFLOW -> WorkflowDefinitionEntity.<WorkflowDefinitionEntity>find("name", name).firstResult().id;
            case ACTION_TYPE -> ActionTypeEntity.<ActionTypeEntity>find("name", name).firstResult().id;
            case TOOL -> ToolDefinitionEntity.<ToolDefinitionEntity>find("name", name).firstResult().id;
            default -> throw new IllegalArgumentException("Unsupported type " + type);
        };
    }

    private static String key(CompositionItemEntity item) {
        return item.itemType + "/" + item.name;
    }
}
