package io.apitomy.axiom.app.validation;

import java.util.Map;
import java.util.Set;

/**
 * Names that items may reference: existing database entities plus items pending in the same pack or session.
 * A null set disables the corresponding existence check.
 *
 * <p>{@code actionTypeInputs} maps action type name to declared input name to whether it is required; null
 * disables node input-mapping checks.
 *
 * <p>{@code actionTypeOutputs} maps action type name to declared output names; null disables the action-type
 * part of the shadowing check.
 *
 * <p>{@code mcpServerNames} are user-defined MCP server names; null disables MCP server reference checks.
 */
public record ValidationContext(Set<String> toolNames, Set<String> toolsetNames, Set<String> actionTypeNames,
                                Set<String> workflowDefinitionNames, Set<Long> workflowDefinitionIds,
                                Set<String> eventSourceIds, Set<String> secretNames,
                                Map<String, Map<String, Boolean>> actionTypeInputs,
                                Map<String, Set<String>> actionTypeOutputs,
                                Set<String> mcpServerNames) {

    /**
     * Creates a context without MCP server names (MCP server reference checks disabled).
     *
     * @param toolNames               tool names
     * @param toolsetNames            toolset names
     * @param actionTypeNames         action type names
     * @param workflowDefinitionNames workflow definition names
     * @param workflowDefinitionIds   workflow definition ids
     * @param eventSourceIds          event source ids
     * @param secretNames             secret names
     * @param actionTypeInputs        action type declared inputs
     * @param actionTypeOutputs       action type declared outputs
     */
    public ValidationContext(Set<String> toolNames, Set<String> toolsetNames, Set<String> actionTypeNames,
                             Set<String> workflowDefinitionNames, Set<Long> workflowDefinitionIds,
                             Set<String> eventSourceIds, Set<String> secretNames,
                             Map<String, Map<String, Boolean>> actionTypeInputs,
                             Map<String, Set<String>> actionTypeOutputs) {
        this(toolNames, toolsetNames, actionTypeNames, workflowDefinitionNames, workflowDefinitionIds,
                eventSourceIds, secretNames, actionTypeInputs, actionTypeOutputs, null);
    }

    /**
     * Creates a context without action type outputs.
     *
     * @param toolNames               tool names
     * @param toolsetNames            toolset names
     * @param actionTypeNames         action type names
     * @param workflowDefinitionNames workflow definition names
     * @param workflowDefinitionIds   workflow definition ids
     * @param eventSourceIds          event source ids
     * @param secretNames             secret names
     * @param actionTypeInputs        action type declared inputs
     */

    public ValidationContext(Set<String> toolNames, Set<String> toolsetNames, Set<String> actionTypeNames,
                             Set<String> workflowDefinitionNames, Set<Long> workflowDefinitionIds,
                             Set<String> eventSourceIds, Set<String> secretNames,
                             Map<String, Map<String, Boolean>> actionTypeInputs) {
        this(toolNames, toolsetNames, actionTypeNames, workflowDefinitionNames, workflowDefinitionIds,
                eventSourceIds, secretNames, actionTypeInputs, null);
    }

    /** @return a context that disables all existence checks (for unit tests without a database) */
    public static ValidationContext unchecked() {
        return new ValidationContext(null, null, null, null, null, null, null, null, null, null);
    }
}
