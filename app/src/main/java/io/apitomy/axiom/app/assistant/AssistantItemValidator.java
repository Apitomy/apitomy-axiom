package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.NewActionType;
import io.apitomy.axiom.api.beans.NewReportDefinition;
import io.apitomy.axiom.api.beans.NewToolDefinition;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.core.entities.ToolsetEntity;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.apitomy.axiom.core.services.ActionTypeValidator;
import io.apitomy.axiom.core.services.ReportDefinitionValidator;
import io.apitomy.axiom.core.services.ToolValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates generated JSON configuration files by delegating to the real
 * validators ({@link ToolValidator}, {@link ActionTypeValidator},
 * {@link ReportDefinitionValidator}).
 *
 * <p>This replaces the earlier thin JSON-level validation with the full
 * semantic validation including placeholder consistency, cross-references,
 * environment variable checks, and more.</p>
 */
@ApplicationScoped
public class AssistantItemValidator {

    private static final Logger LOG = Logger.getLogger(AssistantItemValidator.class);

    @Inject
    ObjectMapper objectMapper;

    /**
     * Validates a JSON file and returns errors and warnings as separate lists.
     *
     * @param file the path to the JSON file
     * @param itemType the item type: "tools", "action-types", or "report-definitions"
     * @return the validation result with separate error and warning lists
     */
    public ValidationResult validate(Path file, String itemType) {
        return validate(file, itemType, null);
    }

    /**
     * Validates a JSON file, including tools from the session's working directory
     * in the known names to avoid false positives for co-created items.
     *
     * @param file the path to the JSON file
     * @param itemType the item type
     * @param workingDirectory the session's working directory, or null
     * @return the validation result
     */
    public ValidationResult validate(Path file, String itemType, Path workingDirectory) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!Files.exists(file)) {
            errors.add("File does not exist: " + file.getFileName());
            return new ValidationResult(errors, warnings);
        }

        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            errors.add("Cannot read file: " + e.getMessage());
            return new ValidationResult(errors, warnings);
        }

        switch (itemType) {
            case "tools" -> validateTool(content, errors, warnings);
            case "action-types" -> validateActionType(content, workingDirectory, errors, warnings);
            case "report-definitions" -> validateReportDefinition(content, workingDirectory, errors, warnings);
            case "toolsets" -> validateToolset(content, errors, warnings);
            case "session-templates" -> validateSessionTemplate(content, errors, warnings);
            case "event-sources" -> validateEventSource(content, errors, warnings);
            default -> errors.add("Unknown item type: " + itemType);
        }

        return new ValidationResult(errors, warnings);
    }

    /**
     * Determines the item type from a file path based on its parent directory.
     *
     * @param file the file path
     * @param workingDirectory the session's working directory
     * @return the item type, or null if not in a known subdirectory
     */
    public String detectItemType(Path file, Path workingDirectory) {
        Path relative = workingDirectory.relativize(file);
        String first = relative.getName(0).toString();
        return switch (first) {
            case "tools" -> "tools";
            case "action-types" -> "action-types";
            case "report-definitions" -> "report-definitions";
            case "toolsets" -> "toolsets";
            case "session-templates" -> "session-templates";
            case "event-sources" -> "event-sources";
            default -> null;
        };
    }

    private void validateTool(String json, List<String> errors, List<String> warnings) {
        NewToolDefinition tool;
        try {
            tool = objectMapper.readValue(json, NewToolDefinition.class);
        } catch (Exception e) {
            errors.add("Invalid JSON: " + e.getMessage());
            return;
        }

        ToolValidator.ValidationResult result = ToolValidator.validate(tool);
        collectMessages(result.errors(), result.warnings(), errors, warnings);
    }

    private void validateActionType(String json, Path workingDirectory,
                                     List<String> errors, List<String> warnings) {
        NewActionType actionType;
        try {
            actionType = objectMapper.readValue(json, NewActionType.class);
        } catch (Exception e) {
            errors.add("Invalid JSON: " + e.getMessage());
            return;
        }

        ActionTypeValidator.KnownNames known = buildActionTypeKnownNames(workingDirectory);
        ActionTypeValidator.ValidationResult result =
                ActionTypeValidator.validate(actionType, known);
        collectMessages(result.errors(), result.warnings(), errors, warnings);
    }

    private void validateReportDefinition(String json, Path workingDirectory,
                                           List<String> errors, List<String> warnings) {
        NewReportDefinition reportDef;
        try {
            reportDef = objectMapper.readValue(json, NewReportDefinition.class);
        } catch (Exception e) {
            errors.add("Invalid JSON: " + e.getMessage());
            return;
        }

        ReportDefinitionValidator.KnownNames known = buildReportDefKnownNames(workingDirectory);
        ReportDefinitionValidator.ValidationResult result =
                ReportDefinitionValidator.validate(reportDef, known);
        collectMessages(result.errors(), result.warnings(), errors, warnings);
    }

    private void validateToolset(String json, List<String> errors, List<String> warnings) {
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            errors.add("Invalid JSON: " + e.getMessage());
            return;
        }

        String name = node.path("name").asText(null);
        if (name == null || name.isBlank()) {
            errors.add("'name' is required and must not be blank.");
        }

        JsonNode toolsNode = node.path("tools");
        if (toolsNode.isMissingNode() || (toolsNode.isArray() && toolsNode.isEmpty())
                || (toolsNode.isTextual() && toolsNode.asText().isBlank())) {
            errors.add("'tools' is required and must contain at least one tool pattern.");
        }

        if (!node.has("description") || node.path("description").asText("").isBlank()) {
            warnings.add("'description' is recommended for toolsets.");
        }
    }

    private void validateSessionTemplate(String json, List<String> errors, List<String> warnings) {
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            errors.add("Invalid JSON: " + e.getMessage());
            return;
        }

        String name = node.path("name").asText(null);
        if (name == null || name.isBlank()) {
            errors.add("'name' is required and must not be blank.");
        }

        String description = node.path("description").asText(null);
        if (description == null || description.isBlank()) {
            errors.add("'description' is required and must not be blank.");
        }

        String systemPrompt = node.path("systemPrompt").asText(null);
        if (systemPrompt == null || systemPrompt.isBlank()) {
            errors.add("'systemPrompt' is required and must not be blank.");
        }

        if (!node.has("templateId") || node.path("templateId").asText("").isBlank()) {
            warnings.add("'templateId' is missing — a UUID will be auto-generated on apply.");
        }

        JsonNode allowedTools = node.path("allowedTools");
        if (allowedTools.isMissingNode() || (allowedTools.isArray() && allowedTools.isEmpty())) {
            warnings.add("'allowedTools' is empty — the session will have no tool access.");
        }
    }

    private void validateEventSource(String json, List<String> errors, List<String> warnings) {
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            errors.add("Invalid JSON: " + e.getMessage());
            return;
        }

        String name = node.path("name").asText(null);
        if (name == null || name.isBlank()) {
            errors.add("'name' is required and must not be blank.");
        }

        String sourceType = node.path("sourceType").asText(null);
        if (sourceType == null || sourceType.isBlank()) {
            errors.add("'sourceType' is required and must be 'github' or 'jira'.");
        } else if (!"github".equals(sourceType) && !"jira".equals(sourceType)) {
            errors.add("'sourceType' must be 'github' or 'jira', got: " + sourceType);
        }

        JsonNode configNode = node.path("configuration");
        if (configNode.isMissingNode() || configNode.isNull()
                || (configNode.isObject() && configNode.isEmpty())) {
            errors.add("'configuration' is required and must not be empty.");
        } else if (configNode.isObject() && sourceType != null) {
            if ("github".equals(sourceType)) {
                if (!configNode.has("owner") || configNode.path("owner").asText("").isBlank()) {
                    errors.add("GitHub configuration requires 'owner' field.");
                }
                if (!configNode.has("name") || configNode.path("name").asText("").isBlank()) {
                    errors.add("GitHub configuration requires 'name' field.");
                }
            } else if ("jira".equals(sourceType)) {
                if (!configNode.has("baseUrl") || configNode.path("baseUrl").asText("").isBlank()) {
                    errors.add("Jira configuration requires 'baseUrl' field.");
                }
                if (!configNode.has("project") || configNode.path("project").asText("").isBlank()) {
                    errors.add("Jira configuration requires 'project' field.");
                }
            }
        }

        if (!node.has("description") || node.path("description").asText("").isBlank()) {
            warnings.add("'description' is recommended for event sources.");
        }
    }

    private <M> void collectMessages(
            List<? extends Record> validationErrors,
            List<? extends Record> validationWarnings,
            List<String> errors, List<String> warnings) {
        for (Record msg : validationErrors) {
            errors.add(formatMessage(msg));
        }
        for (Record msg : validationWarnings) {
            warnings.add(formatMessage(msg));
        }
    }

    private String formatMessage(Record msg) {
        // All three validator message records have field() and message() methods
        // but they're different record types. Use reflection-free approach via toString.
        if (msg instanceof ToolValidator.ValidationMessage m) {
            return m.field() + ": " + m.message();
        } else if (msg instanceof ActionTypeValidator.ValidationMessage m) {
            return m.field() + ": " + m.message();
        } else if (msg instanceof ReportDefinitionValidator.ValidationMessage m) {
            return m.field() + ": " + m.message();
        }
        return msg.toString();
    }

    private ActionTypeValidator.KnownNames buildActionTypeKnownNames(Path workingDirectory) {
        ManagedContext requestContext = Arc.container() != null
                ? Arc.container().requestContext() : null;
        boolean activated = requestContext != null && !requestContext.isActive();
        if (activated) requestContext.activate();
        try {
            Set<String> secrets = SecretEntity.<SecretEntity>listAll().stream()
                    .map(s -> s.name)
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> tools = new java.util.HashSet<>(
                    ToolDefinitionEntity.<ToolDefinitionEntity>listAll().stream()
                            .map(t -> t.name).toList());
            tools.addAll(getSessionToolNames(workingDirectory));
            Set<String> toolsets = ToolsetEntity.<ToolsetEntity>listAll().stream()
                    .map(t -> t.name)
                    .collect(java.util.stream.Collectors.toSet());
            return new ActionTypeValidator.KnownNames(secrets, tools, toolsets, null);
        } catch (Exception e) {
            LOG.warnf("Failed to build KnownNames for validation: %s", e.getMessage());
            return null;
        } finally {
            if (activated) requestContext.deactivate();
        }
    }

    private ReportDefinitionValidator.KnownNames buildReportDefKnownNames(Path workingDirectory) {
        ManagedContext requestContext = Arc.container() != null
                ? Arc.container().requestContext() : null;
        boolean activated = requestContext != null && !requestContext.isActive();
        if (activated) requestContext.activate();
        try {
            Set<String> secrets = SecretEntity.<SecretEntity>listAll().stream()
                    .map(s -> s.name)
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> tools = new java.util.HashSet<>(
                    ToolDefinitionEntity.<ToolDefinitionEntity>listAll().stream()
                            .map(t -> t.name).toList());
            tools.addAll(getSessionToolNames(workingDirectory));
            Set<String> toolsets = ToolsetEntity.<ToolsetEntity>listAll().stream()
                    .map(t -> t.name)
                    .collect(java.util.stream.Collectors.toSet());
            return new ReportDefinitionValidator.KnownNames(secrets, tools, toolsets, null);
        } catch (Exception e) {
            LOG.warnf("Failed to build KnownNames for validation: %s", e.getMessage());
            return null;
        } finally {
            if (activated) requestContext.deactivate();
        }
    }

    /**
     * Scans the session's tools/ directory for JSON files and returns their
     * names (without .json extension). These represent tools being created
     * in the same session that haven't been imported yet.
     */
    private Set<String> getSessionToolNames(Path workingDirectory) {
        if (workingDirectory == null) {
            return Set.of();
        }
        Path toolsDir = workingDirectory.resolve("tools");
        if (!Files.isDirectory(toolsDir)) {
            return Set.of();
        }
        try (java.util.stream.Stream<Path> files = Files.list(toolsDir)) {
            return files.filter(f -> f.toString().endsWith(".json"))
                    .map(f -> f.getFileName().toString().replaceFirst("\\.json$", ""))
                    .collect(java.util.stream.Collectors.toSet());
        } catch (IOException e) {
            return Set.of();
        }
    }

    /**
     * Result of validating a generated item, with errors and warnings separated.
     *
     * @param errors blocking issues that must be fixed
     * @param warnings advisory issues that don't block apply
     */
    public record ValidationResult(List<String> errors, List<String> warnings) {

        /**
         * Returns true if there are no errors (warnings alone are OK).
         */
        public boolean isValid() {
            return errors.isEmpty();
        }

        /**
         * Returns all messages (errors + warnings) combined.
         */
        public List<String> allMessages() {
            List<String> all = new ArrayList<>(errors);
            all.addAll(warnings);
            return all;
        }
    }
}
