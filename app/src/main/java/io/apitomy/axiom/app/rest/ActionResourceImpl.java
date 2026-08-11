package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.ActionResource;
import io.apitomy.axiom.api.beans.ActionType;
import io.apitomy.axiom.api.beans.ActionTypeSearchResults;
import io.apitomy.axiom.api.beans.NewActionType;
import io.apitomy.axiom.api.beans.ReportAiEditRequest;
import io.apitomy.axiom.api.beans.ReportAiEditResponse;
import io.apitomy.axiom.api.beans.ScriptAiEditRequest;
import io.apitomy.axiom.api.beans.ScriptAiEditResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.Environment;
import io.apitomy.axiom.app.ActionTypeAiService;
import io.apitomy.axiom.app.ScriptAiService;
import io.apitomy.axiom.api.beans.ToolValidationResult;
import io.apitomy.axiom.api.beans.ToolValidationMessage;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.core.entities.ToolsetEntity;
import io.apitomy.axiom.core.services.ActionTypeValidator;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of the Action Types REST API.
 */
@ApplicationScoped
@RunOnVirtualThread
public class ActionResourceImpl implements ActionResource {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ScriptAiService scriptAiService;

    @Inject
    ActionTypeAiService actionTypeAiService;

    /**
     * {@inheritDoc}
     */
    @Override
    public ActionTypeSearchResults listActionTypes(BigInteger page, BigInteger limit,
                                                   String filterName, String filterMode,
                                                   String filterLabels) {
        int pageNum = page != null ? page.intValue() : 1;
        int pageSize = limit != null ? limit.intValue() : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (filterName != null && !filterName.isBlank()) {
            hql.append(" and (lower(name) like :name or lower(description) like :name)");
            params.put("name", "%" + filterName.toLowerCase() + "%");
        }

        if (filterMode != null && !filterMode.isBlank()) {
            hql.append(" and executionMode = :mode");
            params.put("mode", filterMode.toLowerCase());
        }

        if (filterLabels != null && !filterLabels.isBlank()) {
            List<String> labels = Arrays.stream(filterLabels.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            hql.append(" and id in (SELECT a.id FROM ActionTypeEntity a"
                    + " JOIN a.labels al WHERE al IN :labels"
                    + " GROUP BY a.id HAVING COUNT(DISTINCT al) = :labelCount)");
            params.put("labels", labels);
            params.put("labelCount", (long) labels.size());
        }

        long totalCount = ActionTypeEntity.count(hql.toString(), params);
        List<ActionType> items = ActionTypeEntity.<ActionTypeEntity>find(
                        hql.toString(), Sort.ascending("name"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list().stream().map(this::toBean).toList();

        ActionTypeSearchResults results = new ActionTypeSearchResults();
        results.setItems(items);
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public ActionType createActionType(NewActionType data) {
        validateOrThrow(data);
        ActionTypeEntity entity = new ActionTypeEntity();
        applyFields(entity, data);
        entity.persist();
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ActionType getActionType(long actionTypeId) {
        return toBean(findOrThrow(actionTypeId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public ActionType updateActionType(long actionTypeId, NewActionType data) {
        validateOrThrow(data);
        ActionTypeEntity entity = findOrThrow(actionTypeId);
        applyFields(entity, data);
        return toBean(entity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void deleteActionType(long actionTypeId) {
        ActionTypeEntity entity = findOrThrow(actionTypeId);
        entity.delete();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolValidationResult validateActionType(NewActionType data) {
        ActionTypeValidator.ValidationResult result =
                ActionTypeValidator.validate(data, getKnownNames());
        ToolValidationResult response = new ToolValidationResult();
        response.setValid(!result.hasErrors());
        response.setMessages(result.messages().stream().map(m -> {
            ToolValidationMessage msg = new ToolValidationMessage();
            msg.setSeverity(m.severity() == ActionTypeValidator.Severity.ERROR
                    ? ToolValidationMessage.Severity.ERROR
                    : ToolValidationMessage.Severity.WARNING);
            msg.setField(m.field());
            msg.setMessage(m.message());
            return msg;
        }).toList());
        return response;
    }

    private void validateOrThrow(NewActionType data) {
        ActionTypeValidator.ValidationResult result =
                ActionTypeValidator.validate(data, getKnownNames());
        if (result.hasErrors()) {
            var errors = result.errors().stream()
                    .map(e -> Map.of("field", e.field(), "message", e.message()))
                    .toList();
            var warnings = result.warnings().stream()
                    .map(w -> Map.of("field", w.field(), "message", w.message()))
                    .toList();
            throw new WebApplicationException(
                    Response.status(422).entity(Map.of(
                            "message", "Action type has validation errors.",
                            "errors", errors,
                            "warnings", warnings
                    )).build());
        }
    }

    private ActionTypeValidator.KnownNames getKnownNames() {
        Set<String> secrets = SecretEntity.<SecretEntity>listAll().stream()
                .map(s -> s.name)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> tools = ToolDefinitionEntity.<ToolDefinitionEntity>listAll().stream()
                .map(t -> t.name)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> toolsets = ToolsetEntity.<ToolsetEntity>listAll().stream()
                .map(t -> t.name)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> sdkTools = Set.of(
                "mcp__axiom-sdk__axiom_fire_event",
                "mcp__axiom-sdk__axiom_list_projects",
                "mcp__axiom-sdk__axiom_get_project",
                "mcp__axiom-sdk__axiom_create_task",
                "mcp__axiom-sdk__axiom_get_task_status",
                "mcp__axiom-sdk__axiom_add_thread_entry",
                "mcp__axiom-sdk__axiom_close_project",
                "mcp__axiom-sdk__axiom_reopen_project",
                "mcp__axiom-sdk__axiom_add_project_label",
                "mcp__axiom-sdk__axiom_remove_project_label",
                "mcp__axiom-sdk__axiom_list_tools",
                "mcp__axiom-sdk__axiom_list_report_definitions"
        );
        return new ActionTypeValidator.KnownNames(secrets, tools, toolsets, sdkTools);
    }

    private void applyFields(ActionTypeEntity entity, NewActionType data) {
        entity.name = data.getName();
        entity.description = data.getDescription();
        entity.executionMode = data.getExecutionMode().value();
        entity.userTriggerable = data.getUserTriggerable() != null ? data.getUserTriggerable() : false;
        entity.managerTriggerable = data.getManagerTriggerable() != null ? data.getManagerTriggerable() : true;
        entity.inputSchema = data.getInputSchema();
        entity.allowedTools = data.getAllowedTools() != null
                ? String.join(", ", data.getAllowedTools()) : null;
        entity.promptTemplate = data.getPromptTemplate();
        entity.scriptTemplate = data.getScriptTemplate();
        entity.model = data.getModel();
        entity.engine = data.getEngine();
        entity.maxSteps = data.getMaxSteps();
        entity.maxBudgetUsd = data.getMaxBudgetUsd();
        entity.emitsEvent = data.getEmitsEvent() != null ? data.getEmitsEvent() : false;
        entity.environment = environmentToJson(data.getEnvironment());
        entity.labels.clear();
        if (data.getLabels() != null) {
            entity.labels.addAll(data.getLabels());
        }
    }

    private ActionTypeEntity findOrThrow(long id) {
        ActionTypeEntity entity = ActionTypeEntity.findById(id);
        if (entity == null) {
            throw new WebApplicationException("Action type not found: " + id, 404);
        }
        return entity;
    }

    private ActionType toBean(ActionTypeEntity entity) {
        ActionType actionType = new ActionType();
        actionType.setId(entity.id);
        actionType.setName(entity.name);
        actionType.setDescription(entity.description);
        actionType.setExecutionMode(ActionType.ExecutionMode.fromValue(entity.executionMode));
        actionType.setUserTriggerable(entity.userTriggerable);
        actionType.setManagerTriggerable(entity.managerTriggerable);
        actionType.setInputSchema(entity.inputSchema);
        if (entity.allowedTools != null && !entity.allowedTools.isBlank()) {
            actionType.setAllowedTools(java.util.Arrays.stream(entity.allowedTools.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList());
        }
        actionType.setPromptTemplate(entity.promptTemplate);
        actionType.setScriptTemplate(entity.scriptTemplate);
        actionType.setModel(entity.model);
        actionType.setEngine(entity.engine);
        actionType.setMaxSteps(entity.maxSteps);
        actionType.setMaxBudgetUsd(entity.maxBudgetUsd);
        actionType.setEmitsEvent(entity.emitsEvent);
        actionType.setEnvironment(jsonToEnvironment(entity.environment));
        actionType.setLabels(entity.labels);
        return actionType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ScriptAiEditResponse aiEditScript(ScriptAiEditRequest data) {
        return scriptAiService.editScript(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ReportAiEditResponse aiEditActionPrompt(ReportAiEditRequest data) {
        return actionTypeAiService.editActionPrompt(data);
    }

    // ── Action Type Tool Associations (deprecated — use allowedTools) ──

    @Override
    public Response listActionTypeTools(BigInteger actionTypeId) {
        // All tools are always available — access controlled by allowedTools
        findOrThrow(actionTypeId.longValue());
        List<ToolDefinitionEntity> allTools = ToolDefinitionEntity.listAll();
        return Response.ok(allTools).build();
    }

    @Override
    @Transactional
    public void updateActionTypeTools(BigInteger actionTypeId) {
        // No-op: tool access is controlled by the action type's allowedTools field
    }

    private String environmentToJson(Environment env) {
        if (env == null || env.getAdditionalProperties().isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(env.getAdditionalProperties());
        } catch (Exception e) {
            return null;
        }
    }

    private Environment jsonToEnvironment(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, String> map = objectMapper.readValue(json, new TypeReference<>() {});
            Environment env = new Environment();
            map.forEach(env::setAdditionalProperty);
            return env;
        } catch (Exception e) {
            return null;
        }
    }
}
