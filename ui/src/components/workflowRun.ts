import type { WorkflowInstance as FlowInstance } from "@apitomy/flow-ui";
import type { WorkflowInstanceInfo } from "../config/api";

/** Label colours of workflow run statuses. */
export const STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    running: "blue",
    waiting: "orange",
    completed: "green",
    failed: "red",
    cancelled: "grey",
};

/**
 * Maps a workflow run to the instance shape the flow viewer renders.
 * @param run the run
 * @returns the viewer instance
 */
export function toFlowInstance(run: WorkflowInstanceInfo): FlowInstance {
    return {
        id: String(run.id),
        workflowId: String(run.definitionId),
        currentNodeId: run.currentNodeId ?? null,
        activeBranches: (run.activeBranches ?? []).map((b) => ({
            branchId: b.branchId ?? "",
            nodeId: b.nodeId ?? "",
        })),
        joinArrivals: {},
        status: run.status as FlowInstance["status"],
        context: run.context || {},
        history: (run.history ?? []).map((h) => ({
            nodeId: h.nodeId, nodeName: h.nodeName,
            edgeId: h.edgeId ?? "", edgeCondition: h.edgeCondition ?? "",
            enteredOn: h.enteredOn, completedOn: h.completedOn ?? "",
            output: h.output ?? {},
            ...(h.input ? { input: h.input } : {}),
            ...(h.title ? { title: h.title } : {}),
        })),
        failureReason: run.failureReason,
        createdOn: run.startedOn,
        updatedOn: run.completedOn ?? run.startedOn,
    } as FlowInstance;
}
