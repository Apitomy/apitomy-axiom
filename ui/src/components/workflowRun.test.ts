import { describe, expect, it } from "vitest";
import type { WorkflowInstanceInfo } from "../config/api";
import { STATUS_COLORS, toFlowInstance } from "./workflowRun";

describe("toFlowInstance", () => {
    it("maps a run like the run page always did", () => {
        const run = {
            id: 4, subjectId: 1, definitionId: 2, definitionVersion: 3, definitionName: "wf", title: "T",
            status: "failed", currentNodeId: "n2", failureReason: "boom", workflowContent: {},
            context: { a: 1 }, startedOn: "2026-10-14T10:00:00Z", completedOn: "2026-10-14T10:01:00Z",
            activeBranches: [{ branchId: "b", nodeId: "n2" }, {}],
            history: [
                { nodeId: "n1", nodeName: "Start", enteredOn: "e1", completedOn: "c1", output: { x: 1 },
                    edgeId: "e", edgeCondition: "ok", input: { i: 1 }, title: "Ti" },
                { nodeId: "n2", nodeName: "Two", enteredOn: "e2" },
            ],
        } as WorkflowInstanceInfo;
        expect(toFlowInstance(run)).toEqual({
            id: "4", workflowId: "2", currentNodeId: "n2",
            activeBranches: [{ branchId: "b", nodeId: "n2" }, { branchId: "", nodeId: "" }],
            joinArrivals: {}, status: "failed", context: { a: 1 },
            history: [
                { nodeId: "n1", nodeName: "Start", edgeId: "e", edgeCondition: "ok", enteredOn: "e1",
                    completedOn: "c1", output: { x: 1 }, input: { i: 1 }, title: "Ti" },
                { nodeId: "n2", nodeName: "Two", edgeId: "", edgeCondition: "", enteredOn: "e2",
                    completedOn: "", output: {} },
            ],
            failureReason: "boom", createdOn: "2026-10-14T10:00:00Z", updatedOn: "2026-10-14T10:01:00Z",
        });
    });

    it("defaults missing fields", () => {
        const run = { id: 1, definitionId: 2, status: "running", startedOn: "s" } as WorkflowInstanceInfo;
        const inst = toFlowInstance(run);
        expect(inst.currentNodeId).toBeNull();
        expect(inst.activeBranches).toEqual([]);
        expect(inst.history).toEqual([]);
        expect(inst.context).toEqual({});
        expect(inst.updatedOn).toBe("s");
    });
});

describe("STATUS_COLORS", () => {
    it("colours run statuses", () => {
        expect(STATUS_COLORS.failed).toBe("red");
        expect(STATUS_COLORS.completed).toBe("green");
    });
});
