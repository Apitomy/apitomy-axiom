package io.apitomy.axiom.core.events.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A routing rule that specifies where matched events should be sent.
 *
 * @param type                  the destination type
 * @param workflowDefinitionId  required for "create-workflow" type
 * @param actionTypeId          required for "invoke-action" type
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoutingRule(
        String type,
        Long workflowDefinitionId,
        Long actionTypeId
) {
    /** Route to the AI Manager for triage. */
    public static final String TYPE_MANAGER = "manager";
    /** Dispatch to waiting workflow receive-event nodes. */
    public static final String TYPE_WORKFLOW_DISPATCH = "workflow-dispatch";
    /** Start a new workflow instance from a definition. */
    public static final String TYPE_CREATE_WORKFLOW = "create-workflow";
    /** Directly invoke an action type. */
    public static final String TYPE_INVOKE_ACTION = "invoke-action";
}
