package io.apitomy.axiom.core.services;

import io.apitomy.axiom.api.beans.NewActionType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates action type definitions and returns a list of errors and warnings.
 *
 * <p>This validator is standalone (no CDI dependencies) so it can be used from
 * REST endpoints, the AI Assistant, or unit tests.</p>
 */
public final class ActionTypeValidator {

    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}\\}");
    private static final Pattern INPUT_PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{inputs\\.([a-zA-Z_][a-zA-Z0-9_]*)\\}\\}");
    private static final Pattern VALID_SHELL_IDENTIFIER_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final Pattern MALFORMED_PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{\\s+|\\s+\\}\\}|\\{\\{[^}]*$");
    /** Former placeholders and how to replace them; any of these in a template is an error. */
    public static final Map<String, String> REMOVED_PLACEHOLDER_HINTS = Map.ofEntries(
            Map.entry("ref", "Declare an input 'ref' and map it from context.ref on the workflow node."),
            Map.entry("repository",
                    "Declare an input 'repository' and map it from context.repository on the workflow node."),
            Map.entry("subjectName",
                    "Declare an input 'subjectName' and map it from context.subjectName on the workflow node."),
            Map.entry("subjectId",
                    "Declare an input 'subjectId' and map it from context.subjectId on the workflow node."),
            Map.entry("eventId",
                    "Declare an input 'eventId' and map it from context.event.id on the workflow node."),
            Map.entry("workDir", "Not a placeholder. Scripts can use $AXIOM_WORK_DIR; agents already run there."),
            Map.entry("apiBaseUrl", "Not a placeholder. Scripts can use $AXIOM_API_URL."),
            Map.entry("taskId", "Not a placeholder. Scripts can use $AXIOM_TASK_ID."),
            Map.entry("actionType", "Not available. Remove it."),
            Map.entry("input", "Not available. Declare named inputs and use {{inputs.<name>}}."));
    private static final Pattern SECRET_REF_PATTERN =
            Pattern.compile("\\$\\{secret:([^}]+)\\}");
    private static final Pattern MALFORMED_SECRET_REF_PATTERN =
            Pattern.compile("\\$\\{secret:\\}|\\$\\{secret:[^}]*$|\\$\\{secret\\}");
    private static final Pattern VALID_ENV_KEY_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final Pattern VALID_FIELD_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /**
     * Known configuration state for cross-referencing. Any field may be null
     * to skip that check.
     */
    public record KnownNames(
            Set<String> secretNames,
            Set<String> toolNames,
            Set<String> toolsetNames,
            Set<String> sdkToolNames) {
    }

    private ActionTypeValidator() {
    }

    /**
     * Validates an action type definition without cross-reference checks.
     *
     * @param def the action type definition to validate
     * @return the validation result
     */
    public static ValidationResult validate(NewActionType def) {
        return validate(def, null);
    }

    /**
     * Validates an action type definition with optional cross-reference checking.
     *
     * @param def the action type definition to validate
     * @param known known configuration names, or null to skip existence checks
     * @return the validation result
     */
    public static ValidationResult validate(NewActionType def, KnownNames known) {
        List<ValidationMessage> messages = new ArrayList<>();

        validateName(def, messages);
        validateDescription(def, messages);
        validateExecutionMode(def, messages);
        validatePromptTemplate(def, messages);
        validateScriptTemplate(def, messages);
        validateCodeTemplate(def, messages);
        validateAllowedTools(def, known, messages);
        validateEnvironment(def, known, messages);
        validateFields(def.getInputs(), "inputs", messages);
        validateFields(def.getOutputs(), "outputs", messages);

        return new ValidationResult(messages);
    }

    private static void validateName(NewActionType def, List<ValidationMessage> messages) {
        if (def.getName() == null || def.getName().isBlank()) {
            messages.add(error("name", "Action type name is required."));
        }
    }

    private static void validateDescription(NewActionType def, List<ValidationMessage> messages) {
        if (def.getDescription() == null || def.getDescription().isBlank()) {
            messages.add(warning("description",
                    "Action type has no description. A description helps users and AI "
                            + "agents decide when to use this action type."));
        }
    }

    private static String executionMode(NewActionType def) {
        return def.getExecutionMode() != null ? def.getExecutionMode().value() : null;
    }

    private static void validateExecutionMode(NewActionType def, List<ValidationMessage> messages) {
        if (def.getExecutionMode() == null) {
            messages.add(error("executionMode", "Execution mode is required."));
        }
    }

    private static void validatePromptTemplate(NewActionType def, List<ValidationMessage> messages) {
        if (!"agent".equals(executionMode(def))) {
            return;
        }
        String prompt = def.getPromptTemplate();
        if (prompt == null || prompt.isBlank()) {
            messages.add(error("promptTemplate",
                    "Prompt template is required for agent-mode action types."));
            return;
        }

        checkMalformedPlaceholders(prompt, "promptTemplate", messages);

        checkUnsupportedPlaceholders(prompt, "promptTemplate", messages);

        checkInputPlaceholders(prompt, "promptTemplate", def, messages);
    }

    private static void validateScriptTemplate(NewActionType def, List<ValidationMessage> messages) {
        if (!"script".equals(executionMode(def))) {
            return;
        }
        String script = def.getScriptTemplate();
        if (script == null || script.isBlank()) {
            messages.add(error("scriptTemplate",
                    "Script template is required for script-mode action types."));
            return;
        }

        checkMalformedPlaceholders(script, "scriptTemplate", messages);

        checkUnsupportedPlaceholders(script, "scriptTemplate", messages);

        checkInputPlaceholders(script, "scriptTemplate", def, messages);
        checkQuotedHeredocPlaceholders(script, messages);

        // Script binding only substitutes inputs whose names are valid shell
        // identifiers; any other declared input is silently skipped at runtime.
        if (def.getInputs() != null) {
            for (io.apitomy.axiom.api.beans.ActionTypeField f : def.getInputs()) {
                String name = f.getName();
                if (name != null && !name.isBlank()
                        && !VALID_SHELL_IDENTIFIER_PATTERN.matcher(name).matches()) {
                    messages.add(warning("scriptTemplate",
                            "Declared input '" + name + "' is not a valid shell identifier "
                                    + "and will be skipped by the script binding path. "
                                    + "Use letters, digits, and underscores; must not start with a digit."));
                }
            }
        }
    }

    /** Axiom-placeholder-shaped text in node code; excludes {@code ${{ ... }}} and mustache sections. */
    private static final java.util.regex.Pattern AXIOM_PLACEHOLDER_IN_CODE =
            java.util.regex.Pattern.compile("(?<!\\$)\\{\\{\\s*[A-Za-z_][\\w.]*\\s*\\}\\}");

    private static void validateCodeTemplate(NewActionType def, List<ValidationMessage> messages) {
        if (!"node".equals(executionMode(def))) {
            return;
        }
        String code = def.getCodeTemplate();
        if (code == null || code.isBlank()) {
            messages.add(error("codeTemplate", "Code is required for node-mode action types."));
            return;
        }
        if (AXIOM_PLACEHOLDER_IN_CODE.matcher(code).find()) {
            messages.add(error("codeTemplate",
                    "Node actions receive inputs as run(inputs, ctx); placeholders are not supported."));
        }
        NodeModuleChecks.importSpecifiers(code).stream()
                .filter(spec -> !NodeModuleChecks.isBuiltin(spec))
                .distinct()
                .forEach(spec -> messages.add(error("codeTemplate", "Import '" + spec
                        + "' is not a Node.js built-in module; only built-ins (e.g. node:fs) are allowed.")));
        if (!NodeModuleChecks.exportsRun(code)) {
            messages.add(error("codeTemplate", "The module must export async function run(inputs, ctx)."));
        }
        try {
            NodeModuleChecks.syntaxError(code).ifPresent(output ->
                    messages.add(error("codeTemplate", "Syntax error: " + errorLine(output))));
        } catch (IllegalStateException e) {
            messages.add(warning("codeTemplate", "node unavailable".equals(e.getMessage())
                    ? "Syntax not checked: Node.js not found."
                    : "Syntax not checked: " + e.getMessage() + "."));
        }
        Set<String> declared = declaredInputNames(def);
        NodeModuleChecks.inputReads(code).stream()
                .filter(name -> !declared.contains(name))
                .forEach(name -> messages.add(warning("codeTemplate",
                        "Reads inputs." + name + ", which is not a declared input.")));
    }

    /**
     * Picks the meaningful line from {@code node --check} output. Node prints the temp file path first, so
     * prefer the first {@code ...Error:} line and fall back to the first non-blank line.
     */
    private static String errorLine(String output) {
        List<String> lines = output.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        return lines.stream()
                .filter(l -> l.matches("^[A-Za-z]*Error:.*"))
                .findFirst()
                .orElse(lines.isEmpty() ? "" : lines.get(0));
    }

    private static void validateAllowedTools(NewActionType def, KnownNames known,
                                              List<ValidationMessage> messages) {
        if (!"agent".equals(executionMode(def))) {
            return;
        }
        List<String> tools = def.getAllowedTools();
        if (tools == null || tools.isEmpty()) {
            return;
        }

        for (int i = 0; i < tools.size(); i++) {
            String tool = tools.get(i);
            if (tool == null || tool.isBlank()) {
                messages.add(error("allowedTools[" + i + "]",
                        "Allowed tool entry at index " + i + " is blank."));
                continue;
            }

            if (tool.startsWith("@")) {
                String toolsetName = tool.substring(1);
                if (known != null && known.toolsetNames() != null
                        && !known.toolsetNames().contains(toolsetName)) {
                    messages.add(error("allowedTools[" + i + "]",
                            "References toolset '@" + toolsetName
                                    + "' but no toolset with that name exists."));
                }
            } else if (tool.startsWith("mcp__axiom-tools__")) {
                String toolName = tool.substring("mcp__axiom-tools__".length());
                if (known != null && known.toolNames() != null
                        && !known.toolNames().contains(toolName)) {
                    messages.add(error("allowedTools[" + i + "]",
                            "References custom tool '" + toolName
                                    + "' but no tool with that name exists."));
                }
            } else if (tool.startsWith("mcp__axiom-sdk__")) {
                if (known != null && known.sdkToolNames() != null
                        && !known.sdkToolNames().contains(tool)) {
                    messages.add(error("allowedTools[" + i + "]",
                            "References SDK tool '" + tool
                                    + "' which is not a recognized Axiom SDK tool."));
                }
            } else if (tool.startsWith("mcp__")) {
                // External MCP server tool — valid pattern
            } else if (!tool.matches("^[A-Za-z].*")) {
                messages.add(warning("allowedTools[" + i + "]",
                        "Tool reference '" + tool
                                + "' does not match any known pattern."));
            }
        }
    }

    private static void validateEnvironment(NewActionType def, KnownNames known,
                                             List<ValidationMessage> messages) {
        var env = def.getEnvironment();
        if (env == null) {
            return;
        }
        Map<String, Object> vars = env.getAdditionalProperties();
        if (vars == null || vars.isEmpty()) {
            return;
        }

        Set<String> knownSecretNames = known != null ? known.secretNames() : null;

        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                messages.add(error("environment", "Environment variable name is blank."));
                continue;
            }
            if (!VALID_ENV_KEY_PATTERN.matcher(key).matches()) {
                messages.add(error("environment." + key,
                        "Environment variable name '" + key
                                + "' is not valid. Use letters, digits, and underscores."));
            }

            String value = entry.getValue() != null ? entry.getValue().toString() : "";

            if (MALFORMED_SECRET_REF_PATTERN.matcher(value).find()) {
                messages.add(error("environment." + key,
                        "Malformed secret reference in value. "
                                + "Use the format ${secret:SECRET_NAME}."));
            }

            Matcher matcher = SECRET_REF_PATTERN.matcher(value);
            while (matcher.find()) {
                String secretName = matcher.group(1);
                if (knownSecretNames != null && !knownSecretNames.contains(secretName)) {
                    messages.add(error("environment." + key,
                            "References secret '${secret:" + secretName
                                    + "}' but no secret named '" + secretName
                                    + "' exists in Axiom."));
                }
            }
        }
    }

    private static void validateFields(java.util.List<io.apitomy.axiom.api.beans.ActionTypeField> fields,
                                       String prefix, List<ValidationMessage> messages) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            io.apitomy.axiom.api.beans.ActionTypeField f = fields.get(i);
            String field = prefix + "[" + i + "]";
            String name = f.getName();
            if (name == null || name.isBlank()) {
                messages.add(error(field, "Field name is required."));
                continue;
            }
            if (!VALID_FIELD_NAME_PATTERN.matcher(name).matches()) {
                messages.add(error(field,
                        "Field name '" + name + "' is not a valid identifier. "
                                + "Use letters, digits, and underscores; must not start with a digit."));
            }
            if (!seen.add(name)) {
                messages.add(error(field, "Field name '" + name + "' is a duplicate within " + prefix + "."));
            }
            if (f.getType() == null) {
                messages.add(error(field, "Field type is required."));
            }
        }
    }

    private static void checkUnsupportedPlaceholders(String text, String field,
                                                     List<ValidationMessage> messages) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            String hint = REMOVED_PLACEHOLDER_HINTS.getOrDefault(name,
                    "Only {{inputs.<name>}} placeholders for declared inputs are supported.");
            messages.add(error(field, "Placeholder '{{" + name + "}}' is not supported. " + hint));
        }
    }

    /**
     * Rejects {@code {{inputs.X}}} inside the body of a quoted heredoc ({@code <<'EOF'}), where bash expands
     * nothing, so the value would never reach the script.
     */
    private static void checkQuotedHeredocPlaceholders(String script, List<ValidationMessage> messages) {
        ShellContext.Context[] contexts = ShellContext.analyze(script);
        Matcher matcher = INPUT_PLACEHOLDER_PATTERN.matcher(script);
        while (matcher.find()) {
            if (contexts[matcher.start()] == ShellContext.Context.QUOTED_HEREDOC) {
                String placeholder = matcher.group();
                messages.add(error("scriptTemplate", placeholder + " is inside a quoted heredoc (<<'EOF'), "
                        + "where bash expands nothing, so the value would never reach the script. Use an "
                        + "unquoted heredoc (<<EOF) or pipe the value: printf '%s' " + placeholder + " | ..."));
            }
        }
    }

    private static void checkInputPlaceholders(String text, String field, NewActionType def,
                                               List<ValidationMessage> messages) {
        Set<String> declared = declaredInputNames(def);
        Matcher matcher = INPUT_PLACEHOLDER_PATTERN.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!declared.contains(name)) {
                messages.add(error(field,
                        "References undeclared input '{{inputs." + name + "}}'. "
                                + "Declare '" + name + "' in the action type's inputs "
                                + "or fix the placeholder name."));
            }
        }
    }

    private static Set<String> declaredInputNames(NewActionType def) {
        Set<String> names = new HashSet<>();
        if (def.getInputs() != null) {
            for (io.apitomy.axiom.api.beans.ActionTypeField f : def.getInputs()) {
                if (f.getName() != null && !f.getName().isBlank()) {
                    names.add(f.getName());
                }
            }
        }
        return names;
    }

    private static void checkMalformedPlaceholders(String text, String field,
                                                    List<ValidationMessage> messages) {
        Matcher malformed = MALFORMED_PLACEHOLDER_PATTERN.matcher(text);
        if (malformed.find()) {
            messages.add(error(field,
                    "Contains a malformed placeholder near: '"
                            + malformed.group().trim() + "'. "
                            + "Use {{name}} with no spaces."));
        }
    }

    private static ValidationMessage error(String field, String message) {
        return new ValidationMessage(Severity.ERROR, field, message);
    }

    private static ValidationMessage warning(String field, String message) {
        return new ValidationMessage(Severity.WARNING, field, message);
    }

    public enum Severity { ERROR, WARNING }

    public record ValidationMessage(Severity severity, String field, String message) {
    }

    public record ValidationResult(List<ValidationMessage> messages) {

        public boolean hasErrors() {
            return messages.stream().anyMatch(m -> m.severity() == Severity.ERROR);
        }

        public boolean hasWarnings() {
            return messages.stream().anyMatch(m -> m.severity() == Severity.WARNING);
        }

        public List<ValidationMessage> errors() {
            return messages.stream().filter(m -> m.severity() == Severity.ERROR)
                    .collect(Collectors.toList());
        }

        public List<ValidationMessage> warnings() {
            return messages.stream().filter(m -> m.severity() == Severity.WARNING)
                    .collect(Collectors.toList());
        }
    }
}
