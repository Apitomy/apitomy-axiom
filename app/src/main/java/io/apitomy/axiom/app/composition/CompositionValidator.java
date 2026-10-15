package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.app.validation.ConfigItemValidationService;
import io.apitomy.axiom.app.validation.ConfigProblem;
import io.apitomy.axiom.app.validation.ValidationContext;
import io.apitomy.axiom.app.validation.ValidationContextFactory;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.events.model.RoutingRule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
            result.put(item.id, validateItem(compositionId, ConfigItemType.fromName(item.itemType), packItem(item), context));
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
        return validateItem(compositionId, type, CompositionItems.toPackItem(type, name, content, mapper),
                context(compositionId));
    }

    /**
     * Validates one item in configuration-pack form: the generic configuration checks plus the composition's own
     * rules (for subscriptions: routes only to this composition's workflow, no draft {@code enabled}, and a
     * warning when another enabled subscription already starts the workflow).
     *
     * @param compositionId the composition id
     * @param type the item type
     * @param packItem the item in configuration-pack form
     * @param context the validation context
     * @return the problems
     */
    public List<ConfigProblem> validateItem(long compositionId, ConfigItemType type, ObjectNode packItem,
                                            ValidationContext context) {
        List<ConfigProblem> problems = new ArrayList<>(validationService.validate(type, packItem, context));
        if (type == ConfigItemType.SUBSCRIPTION) {
            problems.addAll(subscriptionProblems(compositionId, packItem));
        }
        return problems;
    }

    private List<ConfigProblem> subscriptionProblems(long compositionId, JsonNode subscription) {
        List<ConfigProblem> problems = new ArrayList<>();
        List<CompositionItemEntity> items = service.items(compositionId);
        String workflowName = items.stream().filter(i -> ConfigItemType.WORKFLOW.jsonName().equals(i.itemType))
                .map(i -> i.name).findFirst().orElse(null);
        if (subscription.has("enabled")) {
            problems.add(ConfigProblem.warning("enabled", "ENABLED_IGNORED",
                    "Subscriptions are published disabled; enable them in the workbench after publishing."));
        }
        JsonNode routing = subscription.path("routing");
        for (int i = 0; i < routing.size(); i++) {
            JsonNode rule = routing.get(i);
            if (!RoutingRule.TYPE_CREATE_WORKFLOW.equals(rule.path("type").asText())) {
                continue;
            }
            boolean own = !rule.has("workflowDefinitionId") && workflowName != null
                    && workflowName.equals(rule.path("workflowDefinitionName").asText());
            if (!own) {
                problems.add(ConfigProblem.error("routing[" + i + "]", "FOREIGN_WORKFLOW_ROUTE",
                        "A create-workflow rule must start this composition's workflow: set "
                                + "workflowDefinitionName to '" + workflowName + "'."));
            }
        }
        if (workflowName != null && !CompositionItems.referencedWorkflows(subscription).isEmpty()) {
            WorkflowDefinitionEntity definition = WorkflowDefinitionEntity.find("name", workflowName).firstResult();
            if (definition != null) {
                Set<Long> owned = items.stream()
                        .filter(i -> ConfigItemType.SUBSCRIPTION.jsonName().equals(i.itemType) && i.baseItemId != null)
                        .map(i -> i.baseItemId).collect(Collectors.toSet());
                for (EventSubscriptionEntity other : EventSubscriptionEntity.<EventSubscriptionEntity>list("enabled", true)) {
                    if (!owned.contains(other.id) && startsWorkflow(other, definition.id)) {
                        problems.add(ConfigProblem.warning("routing", "OVERLAPPING_SUBSCRIPTION",
                                "Enabled subscription '" + other.name + "' already starts workflow '"
                                        + workflowName + "'."));
                    }
                }
            }
        }
        return problems;
    }

    private boolean startsWorkflow(EventSubscriptionEntity subscription, long definitionId) {
        if (subscription.routing == null) {
            return false;
        }
        try {
            for (JsonNode rule : mapper.readTree(subscription.routing)) {
                if (RoutingRule.TYPE_CREATE_WORKFLOW.equals(rule.path("type").asText())
                        && rule.path("workflowDefinitionId").asLong(-1) == definitionId) {
                    return true;
                }
            }
        } catch (JsonProcessingException e) {
            return false;
        }
        return false;
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
