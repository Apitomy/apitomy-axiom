package io.apitomy.axiom.app.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.NewActionType;
import io.apitomy.axiom.api.beans.NewToolDefinition;
import io.apitomy.axiom.core.SdkFunctionRegistry;
import io.apitomy.axiom.core.events.model.EventType;
import io.apitomy.axiom.events.github.v2.GitHubUrls;
import io.apitomy.axiom.core.events.model.RoutingRule;
import io.apitomy.axiom.core.filters.SubscriptionFilterEvaluator;
import io.apitomy.axiom.core.services.ActionTypeValidator;
import io.apitomy.axiom.core.services.ToolValidator;
import io.apitomy.flow.model.NodeType;
import io.apitomy.flow.model.Workflow;
import io.apitomy.flow.model.WorkflowNode;
import io.apitomy.flow.validation.ValidationProblem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates configuration items (tools, action types, toolsets, session templates, workflows, event sources,
 * subscriptions) and reports problems with JSON paths and stable codes. Shared by the REST API, the
 * configuration assistant and pack import.
 */
@ApplicationScoped
public class ConfigItemValidationService {

    private static final String AXIOM_TOOL_PREFIX = "mcp__axiom-tools__";
    private static final Pattern EVENT_SOURCE_ID = Pattern.compile("[a-z0-9-]+");
    private static final String WORKFLOW_FORMAT_HINT = "Node types are lowercase (start, end, action, "
            + "human-task, receive-event, wait); edges use id/source/target/condition/priority/isDefault (and isTimeout for a receive-event timeout edge).";
    private static final Pattern GITHUB_REPOSITORY = Pattern.compile("[^/\\s]+/[^/\\s]+");
    private static final Pattern FILTER_EVENT_TYPE = Pattern.compile(
            "event\\.type\\s*==\\s*['\"]([^'\"]+)['\"]");

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SubscriptionFilterEvaluator filterEvaluator;

    /**
     * Validates a single configuration item.
     *
     * @param type    the item type
     * @param item    the item JSON
     * @param context names the item may reference
     * @return the problems found (empty when valid)
     */
    public List<ConfigProblem> validate(ConfigItemType type, JsonNode item, ValidationContext context) {
        List<ConfigProblem> problems = new ArrayList<>();
        switch (type) {
            case TOOL -> validateTool(item, problems);
            case ACTION_TYPE -> validateActionType(item, context, problems);
            case TOOLSET -> validateToolset(item, context, problems);
            case SESSION_TEMPLATE -> validateSessionTemplate(item, problems);
            case EVENT_SOURCE -> validateEventSource(item, context, problems);
            case WORKFLOW -> validateWorkflow(item, context, problems);
            case SUBSCRIPTION -> validateSubscription(item, context, problems);
            default -> throw new IllegalArgumentException("Unsupported item type: " + type);
        }
        return problems;
    }

    /**
     * Checks whether any problem is an error.
     *
     * @param problems the problems
     * @return true if at least one problem has error severity
     */
    public static boolean hasErrors(List<ConfigProblem> problems) {
        return problems.stream().anyMatch(ConfigProblem::isError);
    }

    private void validateTool(JsonNode item, List<ConfigProblem> problems) {
        NewToolDefinition tool;
        try {
            tool = objectMapper.treeToValue(item, NewToolDefinition.class);
        } catch (Exception e) {
            problems.add(ConfigProblem.error("", "PARSE_ERROR", "Invalid tool definition: " + e.getMessage()));
            return;
        }
        ToolValidator.validate(tool).messages().forEach(m -> problems.add(new ConfigProblem(
                m.severity().name().toLowerCase(Locale.ROOT), m.field(), "TOOL_INVALID", m.message())));
    }

    private void validateActionType(JsonNode item, ValidationContext context, List<ConfigProblem> problems) {
        NewActionType actionType;
        try {
            actionType = objectMapper.treeToValue(item, NewActionType.class);
        } catch (Exception e) {
            problems.add(ConfigProblem.error("", "PARSE_ERROR", "Invalid action type: " + e.getMessage()));
            return;
        }
        ActionTypeValidator.KnownNames known = new ActionTypeValidator.KnownNames(context.secretNames(),
                context.toolNames(), context.toolsetNames(), SdkFunctionRegistry.allToolNames());
        ActionTypeValidator.validate(actionType, known).messages().forEach(m -> problems.add(new ConfigProblem(
                m.severity().name().toLowerCase(Locale.ROOT), m.field(), "ACTION_TYPE_INVALID", m.message())));
    }

    private void validateToolset(JsonNode item, ValidationContext context, List<ConfigProblem> problems) {
        requireText(item, "name", problems);
        JsonNode tools = item.path("tools");
        if (!tools.isArray() || tools.isEmpty()) {
            problems.add(ConfigProblem.error("tools", "INVALID_FIELD",
                    "'tools' is required and must be a non-empty array of tool patterns."));
        } else {
            for (int i = 0; i < tools.size(); i++) {
                JsonNode entry = tools.get(i);
                String path = "tools[" + i + "]";
                if (!entry.isTextual()) {
                    problems.add(ConfigProblem.error(path, "INVALID_FIELD", "Tool entries must be strings."));
                    continue;
                }
                String value = entry.asText();
                if (value.startsWith(AXIOM_TOOL_PREFIX)) {
                    String toolName = value.substring(AXIOM_TOOL_PREFIX.length());
                    if (context.toolNames() != null && !context.toolNames().contains(toolName)) {
                        problems.add(ConfigProblem.error(path, "UNKNOWN_TOOL", "References custom tool '"
                                + toolName + "' but no tool with that name exists or is pending."));
                    }
                } else if (value.startsWith("@")) {
                    String toolsetName = value.substring(1);
                    if (context.toolsetNames() != null && !context.toolsetNames().contains(toolsetName)) {
                        problems.add(ConfigProblem.error(path, "UNKNOWN_TOOLSET", "References toolset '@"
                                + toolsetName + "' but no toolset with that name exists or is pending."));
                    }
                }
            }
        }
        if (item.path("description").asText("").isBlank()) {
            problems.add(ConfigProblem.warning("description", "MISSING_DESCRIPTION",
                    "'description' is recommended for toolsets."));
        }
    }

    private void validateSessionTemplate(JsonNode item, List<ConfigProblem> problems) {
        requireText(item, "name", problems);
        requireText(item, "description", problems);
        requireText(item, "systemPrompt", problems);
        if (item.path("templateId").asText("").isBlank()) {
            problems.add(ConfigProblem.warning("templateId", "MISSING_TEMPLATE_ID",
                    "'templateId' is missing — a UUID will be auto-generated on apply."));
        }
        JsonNode allowedTools = item.path("allowedTools");
        if (allowedTools.isMissingNode() || (allowedTools.isArray() && allowedTools.isEmpty())) {
            problems.add(ConfigProblem.warning("allowedTools", "NO_ALLOWED_TOOLS",
                    "'allowedTools' is empty — the session will have no tool access."));
        }
    }

    private void validateEventSource(JsonNode item, ValidationContext context, List<ConfigProblem> problems) {
        String id = item.path("id").asText("");
        if (id.isBlank()) {
            problems.add(ConfigProblem.error("id", "MISSING_FIELD", "'id' is required and must not be blank."));
        } else if (!EVENT_SOURCE_ID.matcher(id).matches()) {
            problems.add(ConfigProblem.error("id", "INVALID_ID",
                    "'id' must match [a-z0-9-]+ (lowercase letters, digits, hyphens), got: " + id));
        }
        requireText(item, "name", problems);
        requireText(item, "baseUrl", problems);

        String sourceType = item.path("sourceType").asText("");
        if (!"github".equals(sourceType) && !"jira".equals(sourceType)) {
            problems.add(ConfigProblem.error("sourceType", "INVALID_SOURCE_TYPE",
                    "'sourceType' must be 'github' or 'jira', got: '" + sourceType + "'."));
        }

        JsonNode configuration = item.path("configuration");
        if (!configuration.isObject()) {
            problems.add(ConfigProblem.error("configuration", "INVALID_CONFIGURATION",
                    "'configuration' is required and must be an object."));
        } else if ("github".equals(sourceType)) {
            validateStringList(configuration.path("repositories"), "configuration.repositories",
                    GITHUB_REPOSITORY, "an 'owner/repo' string", problems);
        } else if ("jira".equals(sourceType)) {
            validateStringList(configuration.path("projects"), "configuration.projects",
                    null, "a non-blank project key", problems);
        }

        String baseUrl = item.path("baseUrl").asText("");
        if ("github".equals(sourceType) && !baseUrl.isBlank()) {
            try {
                GitHubUrls.resolve(baseUrl);
            } catch (IllegalArgumentException e) {
                problems.add(ConfigProblem.error("baseUrl", "INVALID_BASE_URL", e.getMessage()
                        + ". Use the GitHub server's URL, e.g. https://github.com or https://github.example.com."));
            }
        }

        String secretName = item.path("secretName").asText("");
        if (!secretName.isBlank() && context.secretNames() != null && !context.secretNames().contains(secretName)) {
            problems.add(ConfigProblem.warning("secretName", "UNKNOWN_SECRET", "Secret '" + secretName
                    + "' does not exist. Create it before enabling this event source."));
        }
    }

    private void validateWorkflow(JsonNode item, ValidationContext context, List<ConfigProblem> problems) {
        requireText(item, "name", problems);
        JsonNode content = item.path("content");
        if (!content.isObject()) {
            problems.add(ConfigProblem.error("content", "MISSING_FIELD",
                    "'content' is required and must be a workflow object."));
            return;
        }
        Workflow workflow;
        try {
            workflow = objectMapper.treeToValue(content, Workflow.class);
        } catch (Exception e) {
            problems.add(ConfigProblem.error("content", "WORKFLOW_PARSE_ERROR",
                    "Invalid workflow content: " + e.getMessage() + " " + WORKFLOW_FORMAT_HINT));
            return;
        }

        for (ValidationProblem p : WorkflowContentRules.engineProblems(workflow)) {
            problems.add(new ConfigProblem(p.severity().name().toLowerCase(Locale.ROOT),
                    engineProblemPath(content, p), p.code(), p.message()));
        }

        Optional<String> inputViolation = WorkflowContentRules.startInputViolation(workflow);
        if (inputViolation.isPresent()) {
            String startId = workflow.findStartNode().map(WorkflowNode::id).orElse(null);
            problems.add(ConfigProblem.error(nodePath(content, startId) + ".config.inputs",
                    "INVALID_START_INPUT", inputViolation.get()));
        }

        List<WorkflowNode> nodes = workflow.nodes() != null ? workflow.nodes() : List.of();
        for (int i = 0; i < nodes.size(); i++) {
            WorkflowNode node = nodes.get(i);
            String path = "content.nodes[" + i + "].config";
            if (node.type() == NodeType.ACTION) {
                validateActionNode(node, path, context, problems);
            } else if (node.type() == NodeType.RECEIVE_EVENT) {
                Object eventType = node.config().get("eventType");
                boolean known = eventType != null && Arrays.stream(EventType.values())
                        .anyMatch(t -> t.value().equals(eventType.toString()));
                if (!known) {
                    String valid = Arrays.stream(EventType.values()).map(EventType::value)
                            .collect(Collectors.joining(", "));
                    problems.add(ConfigProblem.warning(path + ".eventType", "UNKNOWN_EVENT_TYPE",
                            "Event type '" + eventType + "' is not recognized. Valid values: " + valid + "."));
                }
            }
        }

        if (context.actionTypeInputs() != null) {
            for (WorkflowContentRules.InputMappingProblem p
                    : WorkflowContentRules.inputMappingProblems(workflow, context.actionTypeInputs())) {
                problems.add(p.error()
                        ? ConfigProblem.error(p.path(), p.code(), p.message())
                        : ConfigProblem.warning(p.path(), p.code(), p.message()));
            }
        }
    }

    private void validateSubscription(JsonNode item, ValidationContext context, List<ConfigProblem> problems) {
        requireText(item, "name", problems);

        String filter = item.path("filterExpression").asText("");
        if (!filter.isBlank()) {
            if (!filterEvaluator.isValid(filter)) {
                problems.add(ConfigProblem.error("filterExpression", "INVALID_FILTER",
                        "Invalid filter expression: " + filter));
            }
            Matcher matcher = FILTER_EVENT_TYPE.matcher(filter);
            while (matcher.find()) {
                String eventType = matcher.group(1);
                if (Arrays.stream(EventType.values()).noneMatch(t -> t.value().equals(eventType))) {
                    String valid = Arrays.stream(EventType.values()).map(EventType::value)
                            .collect(Collectors.joining(", "));
                    problems.add(ConfigProblem.warning("filterExpression", "UNKNOWN_EVENT_TYPE",
                            "Event type '" + eventType + "' is not recognized. Valid values: " + valid + "."));
                }
            }
        }

        JsonNode routing = item.path("routing");
        for (int i = 0; i < routing.size(); i++) {
            validateRoutingRule(routing.get(i), "routing[" + i + "]", context, problems);
        }
    }

    private static void validateRoutingRule(JsonNode rule, String path, ValidationContext context,
                                            List<ConfigProblem> problems) {
        JsonNode typeNode = rule.path("type");
        if (typeNode.isMissingNode() || typeNode.isNull()) {
            problems.add(ConfigProblem.error(path + ".type", "MISSING_FIELD",
                    "Routing rule missing required 'type' field"));
            return;
        }
        String type = typeNode.asText();
        if (RoutingRule.TYPE_WORKFLOW_DISPATCH.equals(type)) {
            return;
        }
        if (!RoutingRule.TYPE_CREATE_WORKFLOW.equals(type)) {
            problems.add(ConfigProblem.error(path + ".type", "UNSUPPORTED_ROUTING_TYPE",
                    "Unsupported routing type: " + type));
            return;
        }
        JsonNode idNode = rule.path("workflowDefinitionId");
        JsonNode nameNode = rule.path("workflowDefinitionName");
        boolean hasId = !idNode.isMissingNode() && !idNode.isNull();
        boolean hasName = nameNode.isTextual() && !nameNode.asText().isBlank();
        if (!hasId && !hasName) {
            problems.add(ConfigProblem.error(path + ".workflowDefinitionId", "MISSING_FIELD",
                    "create-workflow routing rule requires workflowDefinitionId or workflowDefinitionName"));
        } else if (hasId && hasName) {
            problems.add(ConfigProblem.error(path, "AMBIGUOUS_WORKFLOW_REFERENCE",
                    "Set either workflowDefinitionId or workflowDefinitionName, not both."));
        } else if (hasId) {
            if (context.workflowDefinitionIds() != null
                    && !context.workflowDefinitionIds().contains(idNode.asLong())) {
                problems.add(ConfigProblem.error(path + ".workflowDefinitionId", "UNKNOWN_WORKFLOW_DEFINITION",
                        "Workflow definition with id " + idNode.asText() + " does not exist."));
            }
        } else if (context.workflowDefinitionNames() != null
                && !context.workflowDefinitionNames().contains(nameNode.asText())) {
            problems.add(ConfigProblem.error(path + ".workflowDefinitionName", "UNKNOWN_WORKFLOW_DEFINITION",
                    "Workflow definition '" + nameNode.asText() + "' does not exist or is pending."));
        }
    }

    private static void validateActionNode(WorkflowNode node, String path, ValidationContext context,
                                           List<ConfigProblem> problems) {
        Object value = node.config().get("actionType");
        String actionType = value != null ? value.toString() : "";
        String actionPath = path + ".actionType";
        if (actionType.isBlank()) {
            problems.add(ConfigProblem.error(actionPath, "MISSING_ACTION_TYPE",
                    "Action node '" + node.id() + "' must set config.actionType."));
        } else if (actionType.startsWith("sdk:")) {
            String fn = actionType.substring("sdk:".length());
            if (!SdkFunctionRegistry.isValidSdkCall(fn)) {
                problems.add(ConfigProblem.error(actionPath, "UNKNOWN_SDK_FUNCTION",
                        "Action node '" + node.id() + "' references unknown SDK function '" + fn + "'."));
            }
        } else if (context.actionTypeNames() != null && !context.actionTypeNames().contains(actionType)) {
            problems.add(ConfigProblem.error(actionPath, "UNKNOWN_ACTION_TYPE",
                    "Action node '" + node.id() + "' references action type '" + actionType
                            + "' which does not exist; create it in action-types/ or use axiom_list_action_types."));
        }
    }

    private static String engineProblemPath(JsonNode content, ValidationProblem problem) {
        if (problem.nodeId() != null) {
            return nodePath(content, problem.nodeId());
        }
        if (problem.edgeId() != null) {
            int index = indexOfId(content.path("edges"), problem.edgeId());
            if (index >= 0) {
                return "content.edges[" + index + "]";
            }
        }
        return "content";
    }

    private static String nodePath(JsonNode content, String nodeId) {
        int index = nodeId != null ? indexOfId(content.path("nodes"), nodeId) : -1;
        return index >= 0 ? "content.nodes[" + index + "]" : "content";
    }

    private static int indexOfId(JsonNode array, String id) {
        for (int i = 0; i < array.size(); i++) {
            if (id.equals(array.get(i).path("id").asText(null))) {
                return i;
            }
        }
        return -1;
    }

    private static void validateStringList(JsonNode list, String path, Pattern pattern, String expected,
                                           List<ConfigProblem> problems) {
        if (!list.isArray() || list.isEmpty()) {
            problems.add(ConfigProblem.error(path, "INVALID_CONFIGURATION",
                    "'" + path + "' is required and must be a non-empty array; each entry must be "
                            + expected + "."));
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            JsonNode entry = list.get(i);
            String value = entry.isTextual() ? entry.asText() : "";
            boolean valid = !value.isBlank() && (pattern == null || pattern.matcher(value).matches());
            if (!valid) {
                problems.add(ConfigProblem.error(path + "[" + i + "]", "INVALID_CONFIGURATION",
                        "Entry must be " + expected + ", got: " + entry + "."));
            }
        }
    }

    private static void requireText(JsonNode item, String field, List<ConfigProblem> problems) {
        JsonNode value = item.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            problems.add(ConfigProblem.error(field, "MISSING_FIELD",
                    "'" + field + "' is required and must be a non-blank string."));
        }
    }
}
