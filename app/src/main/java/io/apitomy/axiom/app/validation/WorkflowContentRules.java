package io.apitomy.axiom.app.validation;

import io.apitomy.flow.model.Workflow;
import io.apitomy.flow.model.WorkflowNode;
import io.apitomy.flow.validation.ValidationProblem;
import io.apitomy.flow.validation.WorkflowValidator;

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
}
