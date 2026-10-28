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
            Map.entry("ref", "Declare an input 'ref' and map it from context.subject.ref on the workflow node."),
            Map.entry("repository",
                    "Declare an input 'repository' and map it from context.subject.repository on the workflow node."),
            Map.entry("subjectName",
                    "Declare an input 'subjectName' and map it from context.subject.name on the workflow node."),
            Map.entry("subjectId",
                    "Declare an input 'subjectId' and map it from context.subject.id on the workflow node."),
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

    /** Engine type whose models must use the {@code provider/model} format. */
    public static final String OPENCODE_ENGINE = "opencode";

    /**
     * Known configuration state for cross-referencing. Any field may be null
     * to skip that check.
     *
     * @param secretNames   existing secret names
     * @param toolNames     existing custom tool names
     * @param toolsetNames  existing toolset names
     * @param sdkToolNames  recognized Axiom SDK tool names
     * @param defaultEngine the engine used when an action type names none, or null if unknown
     * @param engineModels  models available per engine type; a missing or empty list skips the
     *                      availability check for that engine
     */
    public record KnownNames(
            Set<String> secretNames,
            Set<String> toolNames,
            Set<String> toolsetNames,
            Set<String> sdkToolNames,
            String defaultEngine,
            Map<String, List<String>> engineModels) {

        /**
         * Creates known names without engine information (skips model availability checks).
         *
         * @param secretNames  existing secret names
         * @param toolNames    existing custom tool names
         * @param toolsetNames existing toolset names
         * @param sdkToolNames recognized Axiom SDK tool names
         */
        public KnownNames(Set<String> secretNames, Set<String> toolNames, Set<String> toolsetNames,
                          Set<String> sdkToolNames) {
            this(secretNames, toolNames, toolsetNames, sdkToolNames, null, null);
        }
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
        validateModel(def, known, messages);
        validateEnvironment(def, known, messages);
        validateUndeclaredSecrets(def, known, messages);
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

    /**
     * Checks an agent action type's model against its engine: OpenCode models must be {@code provider/model}
     * (an error, since the run would always fail), and a model the engine does not report as available is a
     * warning (discovery may be incomplete, and some engines accept aliases).
     */
    private static void validateModel(NewActionType def, KnownNames known, List<ValidationMessage> messages) {
        if (!"agent".equals(executionMode(def))) {
            return;
        }
        String model = def.getModel();
        if (model == null || model.isBlank()) {
            return;
        }
        String engine = def.getEngine() != null && !def.getEngine().isBlank() ? def.getEngine()
                : known != null ? known.defaultEngine() : null;
        if (!OPENCODE_ENGINE.equals(engine)) {
            return;
        }
        int slash = model.indexOf('/');
        if (slash <= 0 || slash == model.length() - 1) {
            messages.add(error("model", "OpenCode models must use the provider/model format (e.g. "
                    + "anthropic/claude-sonnet-4-5); '" + model + "' has no provider."));
            return;
        }
        List<String> available = known != null && known.engineModels() != null
                ? known.engineModels().get(engine) : null;
        if (available != null && !available.isEmpty() && !available.contains(model)) {
            messages.add(warning("model", "Model '" + model + "' is not among the models OpenCode reports as "
                    + "available on this server, so runs will likely fail. Run 'opencode models' to see them."));
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

    /**
     * Warns when script or Node code uses a secret by name ({@code $NAME}, {@code ${NAME}},
     * {@code process.env.NAME}) that the environment does not declare: secrets are not injected automatically,
     * so the variable would be empty at run time. Prompts of agent action types cannot be checked this way.
     */
    private static void validateUndeclaredSecrets(NewActionType def, KnownNames known,
                                                  List<ValidationMessage> messages) {
        if (known == null || known.secretNames() == null || known.secretNames().isEmpty()) {
            return;
        }
        Set<String> declared = def.getEnvironment() != null && def.getEnvironment().getAdditionalProperties() != null
                ? def.getEnvironment().getAdditionalProperties().keySet() : Set.of();
        String mode = def.getExecutionMode() != null ? def.getExecutionMode().value() : "";
        String field = "node".equals(mode) ? "codeTemplate" : "script".equals(mode) ? "scriptTemplate" : null;
        String code = "node".equals(mode) ? def.getCodeTemplate() : "script".equals(mode) ? def.getScriptTemplate()
                : null;
        if (field == null || code == null) {
            return;
        }
        for (String secret : usedSecrets(code, known.secretNames(), "node".equals(mode))) {
            if (!declared.contains(secret)) {
                messages.add(warning(field, undeclaredSecretMessage(secret)));
            }
        }
    }

    /**
     * Returns the secrets a script or Node module refers to by name as environment variables.
     *
     * @param code        the script or module source
     * @param secretNames the names of existing secrets
     * @param node        whether the code is a Node module ({@code process.env}) rather than a shell script
     * @return the referenced secret names, sorted
     */
    public static Set<String> usedSecrets(String code, Set<String> secretNames, boolean node) {
        Set<String> used = new java.util.TreeSet<>();
        for (String secret : secretNames) {
            String quoted = Pattern.quote(secret);
            Pattern use = node
                    ? Pattern.compile("process\\.env(?:\\." + quoted + "(?![A-Za-z0-9_$])|\\[\\s*['\"`]" + quoted
                            + "['\"`]\\s*])")
                    : Pattern.compile("\\$(?:" + quoted + "(?![A-Za-z0-9_])|\\{" + quoted + "(?![A-Za-z0-9_]))");
            if (use.matcher(code).find()) {
                used.add(secret);
            }
        }
        return used;
    }

    /**
     * The warning for a secret used in code but not declared in the environment.
     *
     * @param secret the secret name
     * @return the message
     */
    public static String undeclaredSecretMessage(String secret) {
        return "Uses the secret " + secret + ", which is not declared in the environment. Secrets are not "
                + "injected automatically: add " + secret + "=${secret:" + secret + "} to the environment.";
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
