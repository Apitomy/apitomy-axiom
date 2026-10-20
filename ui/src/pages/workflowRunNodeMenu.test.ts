import { describe, expect, it, vi } from "vitest";
import { buildRunNodeMenuItems, workflowEditorNodeUrl } from "./workflowRunNodeMenu";

const callbacks = () => ({ openLog: vi.fn(), viewTrace: vi.fn(), openInEditor: vi.fn() });

describe("buildRunNodeMenuItems", () => {
    it("offers to open the node in the workflow editor when the run has a definition", () => {
        const cb = callbacks();
        const items = buildRunNodeMenuItems({ definitionId: 4, history: [] }, "step-1", cb);
        const open = items.find((i) => i.id === "open-in-editor");
        expect(open?.label).toBe("Open in workflow editor");
        open?.onSelect("step-1");
        expect(cb.openInEditor).toHaveBeenCalledWith("/components/workflows/4?node=step-1");
    });

    it("omits the editor item when the run has no definition", () => {
        const items = buildRunNodeMenuItems({ history: [] }, "step-1", callbacks());
        expect(items.map((i) => i.id)).not.toContain("open-in-editor");
    });

    it("keeps the log and trace items", () => {
        const cb = callbacks();
        const items = buildRunNodeMenuItems(
            { definitionId: 4, traceId: "t", history: [{ nodeId: "step-1", taskId: 9 }] }, "step-1", cb);
        expect(items.map((i) => i.id)).toEqual(["open-log", "view-trace", "open-in-editor"]);
        items[0].onSelect("step-1");
        expect(cb.openLog).toHaveBeenCalledWith(9);
        items[1].onSelect("step-1");
        expect(cb.viewTrace).toHaveBeenCalled();
    });
});

describe("workflowEditorNodeUrl", () => {
    it("encodes the node id", () => {
        expect(workflowEditorNodeUrl(4, "a b/c")).toBe("/components/workflows/4?node=a%20b%2Fc");
    });
});
