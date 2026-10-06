package io.apitomy.axiom.app.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.NewActionType;
import io.apitomy.axiom.api.beans.NewToolDefinition;
import io.apitomy.axiom.core.SdkFunctionRegistry;
import io.apitomy.axiom.core.filters.SubscriptionFilterEvaluator;
import io.apitomy.axiom.core.services.ActionTypeValidator;
import io.apitomy.axiom.core.services.ToolValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validates configuration items (tools, action types, toolsets, session templates, workflows, event sources,
 * subscriptions) and reports problems with JSON paths and stable codes. Shared by the REST API, the
 * configuration assistant and pack import.
 */
@ApplicationScoped
public class ConfigItemValidationService {

    private static final String AXIOM_TOOL_PREFIX = "mcp__axiom-tools__";
    private static final Pattern EVENT_SOURCE_ID = Pattern.compile("[a-z0-9-]+");
    private static final Pattern GITHUB_REPOSITORY = Pattern.compile("[^/\\s]+/[^/\\s]+");

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
            case WORKFLOW, SUBSCRIPTION -> {
                // Implemented in later tasks.
            }
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

        String secretName = item.path("secretName").asText("");
        if (!secretName.isBlank() && context.secretNames() != null && !context.secretNames().contains(secretName)) {
            problems.add(ConfigProblem.warning("secretName", "UNKNOWN_SECRET", "Secret '" + secretName
                    + "' does not exist. Create it before enabling this event source."));
        }
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
