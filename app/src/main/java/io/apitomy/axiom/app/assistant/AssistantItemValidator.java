package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.app.validation.ConfigItemValidationService;
import io.apitomy.axiom.app.validation.ConfigProblem;
import io.apitomy.axiom.app.validation.ValidationContext;
import io.apitomy.axiom.app.validation.ValidationContextFactory;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates configuration item files written by the configuration assistant by delegating to the shared
 * {@link ConfigItemValidationService}, so the assistant sees exactly the rules applied on apply.
 */
@ApplicationScoped
public class AssistantItemValidator {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ConfigItemValidationService validationService;

    @Inject
    ValidationContextFactory contextFactory;

    /**
     * Validates a JSON file without session context (referenced names are not checked).
     *
     * @param file the path to the JSON file
     * @param itemType the item type (JSON name or directory name)
     * @return the validation result with separate error and warning lists
     */
    public ValidationResult validate(Path file, String itemType) {
        return validate(file, itemType, null);
    }

    /**
     * Validates a JSON file. Items in the session working directory count as existing when checking
     * references.
     *
     * @param file the path to the JSON file
     * @param itemType the item type (JSON name or directory name)
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

        ConfigItemType type;
        try {
            type = ConfigItemType.fromName(itemType);
        } catch (IllegalArgumentException e) {
            errors.add("Unknown item type: " + itemType);
            return new ValidationResult(errors, warnings);
        }

        JsonNode item;
        try {
            item = objectMapper.readTree(Files.readString(file));
        } catch (IOException e) {
            errors.add("Invalid JSON: " + e.getMessage());
            return new ValidationResult(errors, warnings);
        }
        if (item == null || !item.isObject()) {
            errors.add("Invalid JSON: the file must contain a JSON object.");
            return new ValidationResult(errors, warnings);
        }

        for (ConfigProblem problem : validateItem(type, item, workingDirectory)) {
            String text = problem.path() == null || problem.path().isEmpty()
                    ? problem.message() : problem.path() + ": " + problem.message();
            (problem.isError() ? errors : warnings).add(text);
        }
        return new ValidationResult(errors, warnings);
    }

    /**
     * Validates an item JSON object. Workflow {@code content} given as a JSON string is parsed first.
     *
     * @param type the item type
     * @param item the item JSON
     * @param workingDirectory the session's working directory (its items count as existing), or null
     * @return the problems found
     */
    public List<ConfigProblem> validateItem(ConfigItemType type, JsonNode item, Path workingDirectory) {
        JsonNode normalized = normalizeWorkflowContent(type, item);
        ManagedContext requestContext = Arc.container() != null ? Arc.container().requestContext() : null;
        boolean activated = requestContext != null && !requestContext.isActive();
        if (activated) {
            requestContext.activate();
        }
        try {
            ValidationContext context = contextFactory == null ? ValidationContext.unchecked()
                    : workingDirectory != null ? contextFactory.forDirectory(workingDirectory)
                    : contextFactory.databaseOnly();
            return validationService.validate(type, normalized, context);
        } finally {
            if (activated) {
                requestContext.deactivate();
            }
        }
    }

    private JsonNode normalizeWorkflowContent(ConfigItemType type, JsonNode item) {
        if (type != ConfigItemType.WORKFLOW || !item.path("content").isTextual()) {
            return item;
        }
        ObjectNode copy = ((ObjectNode) item).deepCopy();
        try {
            copy.set("content", objectMapper.readTree(item.path("content").asText()));
        } catch (IOException e) {
            // leave as-is; the service reports the non-object content
        }
        return copy;
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
            case "toolsets" -> "toolsets";
            case "session-templates" -> "session-templates";
            case "event-sources" -> "event-sources";
            case "subscriptions" -> "subscriptions";
            case "workflows" -> "workflows";
            default -> null;
        };
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
