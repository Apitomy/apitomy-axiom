package io.apitomy.axiom.core.events.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A routing rule that specifies where matched events should be sent.
 *
 * @param type                  the destination type
 * @param workflowDefinitionId  required for "create-workflow" type
 * @param workflowDefinitionName alternative to the id for "create-workflow"; resolved to the id when saved
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoutingRule(
        String type,
        Long workflowDefinitionId,
        String workflowDefinitionName
) {
    /** Dispatch to waiting workflow receive-event nodes. */
    public static final String TYPE_WORKFLOW_DISPATCH = "workflow-dispatch";
    /** Start a new workflow instance from a definition. */
    public static final String TYPE_CREATE_WORKFLOW = "create-workflow";
}
