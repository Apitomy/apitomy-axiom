package io.apitomy.axiom.manager;

/**
 * Represents a single decision made by the AI Manager after evaluating an event.
 * The Manager may return one or more decisions per event.
 */
public record ManagerDecision(
        /**
         * The type of decision: "create_task", "ignore", "script_action", "escalate"
         */
        String decision,

        /**
         * The action type name for create_task and script_action decisions.
         * Null for ignore/escalate.
         */
        String actionType,

        /**
         * Optional hint for which agent should be assigned the task.
         */
        String agentHint,

        /**
         * Context or instructions to pass to the agent as task input.
         */
        String inputContext,

        /**
         * The Manager's self-assessed confidence in this decision (0.0 to 1.0).
         */
        double confidence,

        /**
         * The Manager's reasoning for this decision.
         */
        String reasoning,

        /**
         * Structured context for human tasks: title, description, reference links.
         * JSON string conforming to the HumanContext schema. Null for non-human tasks.
         */
        String humanContext,

        /**
         * Schema defining the form fields the human must fill in.
         * JSON string conforming to the OutputSchema schema. Null means freeform text.
         */
        String outputSchema
) {

    /**
     * @return true if this is a create_task decision
     */
    public boolean isCreateTask() {
        return "create_task".equals(decision);
    }

    /**
     * @return true if this is an ignore decision
     */
    public boolean isIgnore() {
        return "ignore".equals(decision);
    }

    /**
     * @return true if this is a script action decision
     */
    public boolean isScriptAction() {
        return "script_action".equals(decision);
    }

    /**
     * @return true if this is an escalation to the user
     */
    public boolean isEscalate() {
        return "escalate".equals(decision);
    }
}
