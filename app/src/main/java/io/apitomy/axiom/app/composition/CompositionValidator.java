package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.app.validation.ConfigItemValidationService;
import io.apitomy.axiom.app.validation.ConfigProblem;
import io.apitomy.axiom.app.validation.ValidationContext;
import io.apitomy.axiom.app.validation.ValidationContextFactory;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates composition drafts with the same rules as publishing, treating the composition's other drafts as
 * existing.
 */
@ApplicationScoped
public class CompositionValidator {

    @Inject
    CompositionService service;

    @Inject
    ConfigItemValidationService validationService;

    @Inject
    ValidationContextFactory contextFactory;

    @Inject
    ObjectMapper mapper;

    /**
     * @param compositionId the composition id
     * @return a validation context of the database plus this composition's drafts
     */
    public ValidationContext context(long compositionId) {
        Map<ConfigItemType, List<JsonNode>> byType = new EnumMap<>(ConfigItemType.class);
        for (CompositionItemEntity item : service.items(compositionId)) {
            byType.computeIfAbsent(ConfigItemType.fromName(item.itemType), t -> new ArrayList<>()).add(packItem(item));
        }
        return contextFactory.forItems(byType);
    }

    /**
     * Validates every draft in the composition.
     *
     * @param compositionId the composition id
     * @return problems by item id, in item order; empty lists for valid items
     */
    public Map<Long, List<ConfigProblem>> validateAll(long compositionId) {
        ValidationContext context = context(compositionId);
        Map<Long, List<ConfigProblem>> result = new LinkedHashMap<>();
        for (CompositionItemEntity item : service.items(compositionId)) {
            result.put(item.id, validationService.validate(ConfigItemType.fromName(item.itemType), packItem(item), context));
        }
        return result;
    }

    /**
     * Validates content that may not be saved yet, for example the editor's current workflow.
     *
     * @param compositionId the composition id
     * @param type the item type
     * @param name the item name (for workflows, the item's name, not {@code content.name})
     * @param content the draft content
     * @return the problems
     */
    public List<ConfigProblem> validateContent(long compositionId, ConfigItemType type, String name, JsonNode content) {
        return validationService.validate(type, CompositionItems.toPackItem(type, name, content, mapper),
                context(compositionId));
    }

    /**
     * @param item a composition item
     * @return its configuration-pack form
     */
    public ObjectNode packItem(CompositionItemEntity item) {
        return CompositionItems.toPackItem(ConfigItemType.fromName(item.itemType), item.name,
                service.contentOf(item), mapper);
    }
}
