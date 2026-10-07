package io.apitomy.axiom.app.validation;

import io.apitomy.flow.engine.ConditionEvaluator;
import io.apitomy.flow.model.NodeConfig;
import io.apitomy.flow.model.NodeType;
import io.apitomy.flow.model.Workflow;
import io.apitomy.flow.model.WorkflowNode;
import io.apitomy.flow.validation.ValidationProblem;
import io.apitomy.flow.validation.WorkflowValidator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rules for workflow content shared by publish and configuration validation. */
public final class WorkflowContentRules {

    /** Built-in Start inputs that may not be marked required. */
    public static final Set<String> OPTIONAL_BUILT_IN_INPUTS = Set.of("subjectName", "repository", "event");
    /** Valid Start input names. */
    public static final Pattern INPUT_NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private WorkflowContentRules() {
    }

    /**
     * Checks Start node input declarations: every name must be a valid identifier, and the built-in inputs
     * Axiom does not always provide may not be marked required.
     *
     * @param workflow the workflow
     * @return the first violation message, or empty if valid
     */
    public static Optional<String> startInputViolation(Workflow workflow) {
        WorkflowNode startNode = workflow.findStartNode().orElse(null);
        if (startNode == null) {
            return Optional.empty(); // missing Start node is handled by structural validation
        }
        Object inputsDef = startNode.config().get("inputs");
        if (!(inputsDef instanceof List<?> inputs)) {
            return Optional.empty();
        }
        for (Object inputObj : inputs) {
            if (!(inputObj instanceof Map<?, ?> input)) {
                continue;
            }
            Object nameObj = input.get("name");
            String name = nameObj != null ? nameObj.toString() : null;
            if (name == null || !INPUT_NAME_PATTERN.matcher(name).matches()) {
                return Optional.of("Start node input '" + name + "' is not a valid input name. "
                        + "Names must start with a letter or underscore and "
                        + "contain only letters, digits and underscores.");
            }
            if (Boolean.TRUE.equals(input.get("required")) && OPTIONAL_BUILT_IN_INPUTS.contains(name)) {
                return Optional.of("Start node input '" + name + "' cannot be marked "
                        + "required because Axiom does not always provide it.");
            }
        }
        return Optional.empty();
    }

    /**
     * Runs the flow engine's validator.
     *
     * @param workflow the workflow
     * @return engine problems (errors and warnings)
     */
    public static List<ValidationProblem> engineProblems(Workflow workflow) {
        return new WorkflowValidator().validate(workflow);
    }

    /**
     * A node input-mapping problem.
     *
     * @param nodeIndex index of the node in content.nodes
     * @param key       the input name concerned
     * @param code      UNMAPPED_REQUIRED_INPUT, UNKNOWN_INPUT_MAPPING, EMPTY_INPUT_MAPPING,
     *                  INVALID_INPUT_EXPRESSION, UNKNOWN_CONTEXT_KEY or BARE_EVENT_REFERENCE
     * @param message   human-readable explanation
     * @param error     true for errors, false for warnings
     */
    public record InputMappingProblem(int nodeIndex, String key, String code, String message, boolean error) {

        /** @return the JSON path of the problem relative to the workflow item */
        public String path() {
            String inputsPath = "content.nodes[" + nodeIndex + "].config.inputs";
            return "UNMAPPED_REQUIRED_INPUT".equals(code) ? inputsPath : inputsPath + "." + key;
        }
    }

    /** Context keys every run provides. */
    public static final Set<String> BUILT_IN_CONTEXT_KEYS =
            Set.of("subjectId", "subjectName", "ref", "repository", "event");

    private static final Pattern CONTEXT_KEY_REFERENCE =
            Pattern.compile("(?<![\\w.])context\\.([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern BARE_EVENT_REFERENCE = Pattern.compile("(?<![\\w.'\"])event\\.");

    /**
     * Checks action node input mappings against the declared inputs of their action types:
     * <ul>
     *   <li>every required declared input is mapped, and no undeclared input is mapped;</li>
     *   <li>a required input is not mapped to a blank expression;</li>
     *   <li>every mapping expression is valid EL;</li>
     *   <li>expressions do not use a bare {@code event.} reference (action inputs see only {@code context});</li>
     *   <li>{@code context.<key>} references name a key that the run provides: a built-in key, a Start input,
     *       or an output some node writes to the context (error for required inputs, warning for optional).</li>
     * </ul>
     * Nodes whose action type is {@code sdk:*} or unknown to {@code declarations} are skipped. Branch order is
     * not analysed, and expressions that evaluate to nothing at run time are still caught when the task starts.
     *
     * @param workflow     the workflow
     * @param declarations action type name to (input name to required)
     * @return problems, empty if valid
     */
    public static List<InputMappingProblem> inputMappingProblems(Workflow workflow,
            Map<String, Map<String, Boolean>> declarations) {
        List<InputMappingProblem> problems = new ArrayList<>();
        List<WorkflowNode> nodes = workflow.nodes() != null ? workflow.nodes() : List.of();
        Set<String> knownKeys = knownContextKeys(nodes);
        ConditionEvaluator evaluator = new ConditionEvaluator();
        for (int i = 0; i < nodes.size(); i++) {
            WorkflowNode node = nodes.get(i);
            if (node.type() != NodeType.ACTION || node.config() == null) {
                continue;
            }
            Object typeObj = node.config().get("actionType");
            String type = typeObj != null ? typeObj.toString() : "";
            if (type.startsWith("sdk:")) {
                continue;
            }
            Map<String, Boolean> declared = declarations.get(type);
            if (declared == null) {
                continue;
            }
            Map<?, ?> mapped = node.config().get("inputs") instanceof Map<?, ?> m ? m : Map.of();
            for (Map.Entry<String, Boolean> input : declared.entrySet()) {
                if (Boolean.TRUE.equals(input.getValue()) && !mapped.containsKey(input.getKey())) {
                    String k = input.getKey();
                    problems.add(new InputMappingProblem(i, k, "UNMAPPED_REQUIRED_INPUT",
                            "Required input '" + k + "' of action type '" + type + "' is not mapped. "
                                    + "Add it to config.inputs (e.g. \"" + k + "\": \"context." + k + "\").", true));
                }
            }
            for (Map.Entry<?, ?> entry : mapped.entrySet()) {
                String k = String.valueOf(entry.getKey());
                if (!declared.containsKey(k)) {
                    problems.add(new InputMappingProblem(i, k, "UNKNOWN_INPUT_MAPPING",
                            "Action type '" + type + "' has no input named '" + k + "'.", true));
                    continue;
                }
                boolean required = Boolean.TRUE.equals(declared.get(k));
                checkExpression(i, k, entry.getValue(), required, knownKeys, evaluator, problems);
            }
        }
        return problems;
    }

    private static void checkExpression(int nodeIndex, String key, Object value, boolean required,
                                        Set<String> knownKeys, ConditionEvaluator evaluator,
                                        List<InputMappingProblem> problems) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            if (required) {
                problems.add(new InputMappingProblem(nodeIndex, key, "EMPTY_INPUT_MAPPING",
                        "Required input '" + key + "' is mapped to an empty expression. Map it to a value, "
                                + "e.g. \"context." + key + "\".", true));
            }
            return;
        }
        if (!(value instanceof String expression)) {
            return;
        }
        if (!evaluator.isValid(expression)) {
            problems.add(new InputMappingProblem(nodeIndex, key, "INVALID_INPUT_EXPRESSION",
                    "Input '" + key + "' is not a valid EL expression: " + expression, true));
            return;
        }
        if (BARE_EVENT_REFERENCE.matcher(expression).find()) {
            problems.add(new InputMappingProblem(nodeIndex, key, "BARE_EVENT_REFERENCE",
                    "Input '" + key + "' uses 'event.' directly; action inputs can only read the run context. "
                            + "Use context.event instead (e.g. context.event.payload).", true));
            return;
        }
        Matcher matcher = CONTEXT_KEY_REFERENCE.matcher(expression);
        while (matcher.find()) {
            String contextKey = matcher.group(1);
            if (!knownKeys.contains(contextKey)) {
                problems.add(new InputMappingProblem(nodeIndex, key, "UNKNOWN_CONTEXT_KEY",
                        "Input '" + key + "' reads context." + contextKey + ", but nothing in this workflow "
                                + "provides '" + contextKey + "'. Known keys: " + new TreeSet<>(knownKeys) + ".",
                        required));
            }
        }
    }

    /**
     * Collects the context keys a workflow can provide: built-in keys, Start node inputs, and outputs written by
     * action, human-task and receive-event nodes.
     *
     * @param nodes the workflow nodes
     * @return the known context keys
     */
    static Set<String> knownContextKeys(List<WorkflowNode> nodes) {
        Set<String> keys = new HashSet<>(BUILT_IN_CONTEXT_KEYS);
        for (WorkflowNode node : nodes) {
            if (node.config() == null || node.type() == null) {
                continue;
            }
            try {
                switch (node.typedConfig()) {
                    case NodeConfig.Start start -> start.inputs().forEach(f -> addKey(keys, f.name()));
                    case NodeConfig.Action action -> action.outputs().forEach(f -> addKey(keys, f.effectiveContextKey()));
                    case NodeConfig.HumanTask task -> task.outputs().forEach(f -> addKey(keys, f.effectiveContextKey()));
                    case NodeConfig.ReceiveEvent receive ->
                            receive.outputs().forEach(m -> addKey(keys, m.contextKey()));
                    default -> {
                    }
                }
            } catch (RuntimeException e) {
                // Malformed config is reported by the engine validator; contribute no keys.
            }
        }
        return keys;
    }

    private static void addKey(Set<String> keys, String key) {
        if (key != null && !key.isBlank()) {
            keys.add(key);
        }
    }
}
