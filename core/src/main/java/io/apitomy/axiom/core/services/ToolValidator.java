package io.apitomy.axiom.core.services;

import io.apitomy.axiom.api.beans.NewToolDefinition;
import io.apitomy.axiom.api.beans.ToolParameter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates tool definitions and returns a list of errors and warnings.
 *
 * <p>This validator is standalone (no CDI dependencies) so it can be used from
 * REST endpoints, the AI Assistant, or unit tests.</p>
 *
 * <p>Usage:</p>
 * <pre>
 *     ToolValidator.ValidationResult result = ToolValidator.validate(toolDefinition);
 *     if (result.hasErrors()) { ... }
 * </pre>
 */
public final class ToolValidator {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}\\}");
    private static final Pattern FILE_PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)_file\\}\\}");
    private static final Pattern MALFORMED_PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s+|\\s+}}|\\{\\{[^}]*$");
    private static final Pattern VALID_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]*$");
    private static final Pattern VALID_PARAM_NAME_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private ToolValidator() {
    }

    /**
     * Validates a tool definition and returns all errors and warnings.
     *
     * @param tool the tool definition to validate
     * @return the validation result containing errors and warnings
     */
    public static ValidationResult validate(NewToolDefinition tool) {
        List<ValidationMessage> messages = new ArrayList<>();

        validateName(tool, messages);
        validateDescription(tool, messages);
        validateScriptTemplate(tool, messages);
        validateParameters(tool, messages);
        validatePlaceholderConsistency(tool, messages);
        validateLabels(tool, messages);

        return new ValidationResult(messages);
    }

    private static void validateName(NewToolDefinition tool, List<ValidationMessage> messages) {
        if (tool.getName() == null || tool.getName().isBlank()) {
            messages.add(error("name", "Tool name is required."));
            return;
        }
        if (!VALID_NAME_PATTERN.matcher(tool.getName()).matches()) {
            messages.add(warning("name",
                    "Tool name should be lowercase with hyphens or underscores "
                            + "(e.g. 'list-github-issues'). Got: '" + tool.getName() + "'."));
        }
    }

    private static void validateDescription(NewToolDefinition tool, List<ValidationMessage> messages) {
        if (tool.getDescription() == null || tool.getDescription().isBlank()) {
            messages.add(warning("description",
                    "Tool has no description. AI agents use the description to "
                            + "understand when and how to use the tool."));
        }
    }

    private static void validateScriptTemplate(NewToolDefinition tool, List<ValidationMessage> messages) {
        String script = tool.getScriptTemplate();
        if (script == null || script.isBlank()) {
            messages.add(error("scriptTemplate", "Script template is required."));
            return;
        }

        Matcher malformed = MALFORMED_PLACEHOLDER_PATTERN.matcher(script);
        if (malformed.find()) {
            messages.add(error("scriptTemplate",
                    "Script template contains a malformed placeholder near: '"
                            + malformed.group() + "'."));
        }
    }

    private static void validateParameters(NewToolDefinition tool, List<ValidationMessage> messages) {
        List<ToolParameter> params = tool.getParameters();
        if (params == null || params.isEmpty()) {
            return;
        }

        Set<String> seenNames = new HashSet<>();
        for (int i = 0; i < params.size(); i++) {
            ToolParameter param = params.get(i);
            String paramName = param.getName();

            if (paramName == null || paramName.isBlank()) {
                messages.add(error("parameters[" + i + "].name",
                        "Parameter at index " + i + " has no name."));
                continue;
            }

            if (!VALID_PARAM_NAME_PATTERN.matcher(paramName).matches()) {
                messages.add(error("parameters[" + i + "].name",
                        "Parameter name '" + paramName + "' is not a valid identifier. "
                                + "Use letters, digits, and underscores (e.g. 'repo_name')."));
            }

            if (!seenNames.add(paramName.toLowerCase())) {
                messages.add(error("parameters[" + i + "].name",
                        "Duplicate parameter name: '" + paramName + "'."));
            }

            if (param.getType() == null) {
                messages.add(error("parameters[" + i + "].type",
                        "Parameter '" + paramName + "' is missing a type."));
            }
        }
    }

    private static void validatePlaceholderConsistency(NewToolDefinition tool,
                                                       List<ValidationMessage> messages) {
        String script = tool.getScriptTemplate();
        if (script == null || script.isBlank()) {
            return;
        }

        // Collect all defined parameter names
        Set<String> definedParams = new HashSet<>();
        if (tool.getParameters() != null) {
            for (ToolParameter param : tool.getParameters()) {
                if (param.getName() != null) {
                    definedParams.add(param.getName());
                }
            }
        }

        // Find all {{name_file}} references first — these reference a parameter
        // named "name" (without the _file suffix)
        Set<String> fileParamNames = new HashSet<>();
        Matcher fileMatcher = FILE_PLACEHOLDER_PATTERN.matcher(script);
        while (fileMatcher.find()) {
            fileParamNames.add(fileMatcher.group(1));
        }

        // Find all {{placeholder}} references, but skip those that are actually
        // _file variants (e.g. {{body_file}} references parameter "body", not
        // a parameter called "body_file")
        Set<String> referencedParams = new HashSet<>(fileParamNames);
        Matcher directMatcher = PLACEHOLDER_PATTERN.matcher(script);
        while (directMatcher.find()) {
            String name = directMatcher.group(1);
            if (name.endsWith("_file") && fileParamNames.contains(
                    name.substring(0, name.length() - 5))) {
                continue;
            }
            referencedParams.add(name);
        }

        // Check for placeholders that reference undefined parameters
        for (String ref : referencedParams) {
            if (!definedParams.contains(ref)) {
                messages.add(error("scriptTemplate",
                        "Script template references '{{" + ref + "}}' but no parameter "
                                + "named '" + ref + "' is defined."));
            }
        }

        // Check for defined parameters not used in the script
        for (String defined : definedParams) {
            if (!referencedParams.contains(defined)) {
                messages.add(warning("parameters",
                        "Parameter '" + defined + "' is defined but never referenced "
                                + "in the script template as {{" + defined + "}} or {{"
                                + defined + "_file}}."));
            }
        }
    }

    private static void validateLabels(NewToolDefinition tool, List<ValidationMessage> messages) {
        List<String> labels = tool.getLabels();
        if (labels == null || labels.isEmpty()) {
            return;
        }

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            if (label == null || label.isBlank()) {
                messages.add(error("labels[" + i + "]", "Label at index " + i + " is blank."));
            } else if (!seen.add(label.toLowerCase())) {
                messages.add(warning("labels", "Duplicate label: '" + label + "'."));
            }
        }
    }

    private static ValidationMessage error(String field, String message) {
        return new ValidationMessage(Severity.ERROR, field, message);
    }

    private static ValidationMessage warning(String field, String message) {
        return new ValidationMessage(Severity.WARNING, field, message);
    }

    /**
     * Severity level for a validation message.
     */
    public enum Severity {
        /** Indicates a problem that must be fixed before the tool can work correctly. */
        ERROR,
        /** Indicates a potential issue that should be reviewed but won't prevent saving. */
        WARNING
    }

    /**
     * A single validation finding.
     *
     * @param severity whether this is an error or warning
     * @param field the field or path that the message relates to
     * @param message human-readable description of the issue
     */
    public record ValidationMessage(Severity severity, String field, String message) {
    }

    /**
     * The result of validating a tool definition.
     *
     * @param messages all validation findings (errors and warnings)
     */
    public record ValidationResult(List<ValidationMessage> messages) {

        /**
         * Returns true if there are any errors (warnings alone return false).
         *
         * @return true if validation errors exist
         */
        public boolean hasErrors() {
            return messages.stream().anyMatch(m -> m.severity() == Severity.ERROR);
        }

        /**
         * Returns true if there are any warnings.
         *
         * @return true if validation warnings exist
         */
        public boolean hasWarnings() {
            return messages.stream().anyMatch(m -> m.severity() == Severity.WARNING);
        }

        /**
         * Returns only the error messages.
         *
         * @return list of error messages
         */
        public List<ValidationMessage> errors() {
            return messages.stream()
                    .filter(m -> m.severity() == Severity.ERROR)
                    .collect(Collectors.toList());
        }

        /**
         * Returns only the warning messages.
         *
         * @return list of warning messages
         */
        public List<ValidationMessage> warnings() {
            return messages.stream()
                    .filter(m -> m.severity() == Severity.WARNING)
                    .collect(Collectors.toList());
        }
    }
}
