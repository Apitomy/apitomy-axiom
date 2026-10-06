package io.apitomy.axiom.app.validation;

import io.apitomy.flow.model.Workflow;
import io.apitomy.flow.model.NodeType;
import io.apitomy.flow.model.WorkflowNode;
import io.apitomy.flow.validation.ValidationProblem;
import io.apitomy.flow.validation.WorkflowValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
     * @param code      UNMAPPED_REQUIRED_INPUT or UNKNOWN_INPUT_MAPPING
     * @param message   human-readable explanation
     */
    public record InputMappingProblem(int nodeIndex, String key, String code, String message) {
    }

    /**
     * Checks that every action node maps each required declared input and maps no undeclared input. Nodes
     * whose action type is {@code sdk:*} or unknown to {@code declarations} are skipped.
     *
     * @param workflow     the workflow
     * @param declarations action type name to (input name to required)
     * @return problems, empty if valid
     */
    public static List<InputMappingProblem> inputMappingProblems(Workflow workflow,
            Map<String, Map<String, Boolean>> declarations) {
        List<InputMappingProblem> problems = new ArrayList<>();
        List<WorkflowNode> nodes = workflow.nodes() != null ? workflow.nodes() : List.of();
        for (int i = 0; i < nodes.size(); i++) {
            WorkflowNode node = nodes.get(i);
            if (node.type() != NodeType.ACTION || node.config() == null) {
                continue;
            }
            Object typeObj = node.config().get("actionType");
            String type = typeObj != null ? typeObj.toString() : "";
            Map<String, Boolean> declared = declarations.get(type);
            if (type.startsWith("sdk:") || declared == null) {
                continue;
            }
            Map<?, ?> mapped = node.config().get("inputs") instanceof Map<?, ?> m ? m : Map.of();
            for (Map.Entry<String, Boolean> input : declared.entrySet()) {
                if (Boolean.TRUE.equals(input.getValue()) && !mapped.containsKey(input.getKey())) {
                    String k = input.getKey();
                    problems.add(new InputMappingProblem(i, k, "UNMAPPED_REQUIRED_INPUT",
                            "Required input '" + k + "' of action type '" + type + "' is not mapped. "
                                    + "Add it to config.inputs (e.g. \"" + k + "\": \"context." + k + "\")."));
                }
            }
            for (Object key : mapped.keySet()) {
                String k = String.valueOf(key);
                if (!declared.containsKey(k)) {
                    problems.add(new InputMappingProblem(i, k, "UNKNOWN_INPUT_MAPPING",
                            "Action type '" + type + "' has no input named '" + k + "'."));
                }
            }
        }
        return problems;
    }
}
