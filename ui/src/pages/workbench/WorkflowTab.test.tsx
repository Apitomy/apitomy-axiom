import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, render, waitFor } from "@testing-library/react";
import { useImperativeHandle } from "react";
import * as c from "../../config/compositions";
import { WorkflowTab } from "./WorkflowTab";

const handle = { apply: vi.fn(), propose: vi.fn(), replace: vi.fn(), withdraw: vi.fn(), clearHighlights: vi.fn(),
    getSnapshot: vi.fn() };
let editorProps: Record<string, any> = {};
vi.mock("@apitomy/flow-ui", () => ({
    WorkflowEditor: (props: Record<string, any>) => {
        editorProps = props;
        useImperativeHandle(props.ref, () => handle);
        return <div data-testid="editor" />;
    },
}));
vi.mock("../../hooks/useTheme", () => ({ useEffectiveTheme: () => "light" }));

const base = {
    compositionId: 1,
    item: { id: 10, type: "workflow", name: "wf", contentRevision: "r0", lastAuthor: "user", changed: true } as c.CompositionItem,
    initialContent: { id: "w", name: "wf", nodes: [], edges: [] },
    composition: { id: 1, name: "C", turnState: "idle", applyMode: "live", createdAt: "", updatedAt: "" } as c.Composition,
    catalog: [], applyLayout: "place" as const, onSaved: vi.fn(), onOpenActionType: vi.fn(), onAskAi: vi.fn(),
    onPendingHandled: vi.fn(),
};

describe("WorkflowTab", () => {
    beforeEach(() => { vi.restoreAllMocks(); Object.values(handle).forEach((f) => f.mockReset()); vi.useRealTimers(); });

    it("applies a live pending change set and resolves it as applied", async () => {
        handle.apply.mockReturnValue({ status: "applied" });
        const resolve = vi.spyOn(c, "resolveChangeSet").mockResolvedValue(undefined);
        const cs = { id: "t1", baseRevision: "r", author: "agent:axiom-workbench", summary: "s", ops: [] };
        render(<WorkflowTab {...base} pending={{ turnId: "t1", changeSet: cs as never, fromPending: true }} />);
        await waitFor(() => expect(resolve).toHaveBeenCalledWith(1, { turnId: "t1", outcome: "applied", fromPending: true }));
        expect(handle.apply).toHaveBeenCalledWith(cs);
        expect(base.onPendingHandled).toHaveBeenCalled();
    });

    it("resolves stale apply results as stale", async () => {
        handle.apply.mockReturnValue({ status: "rejected", error: { code: "stale", reason: "old" } });
        const resolve = vi.spyOn(c, "resolveChangeSet").mockResolvedValue(undefined);
        render(<WorkflowTab {...base} pending={{ turnId: "t2", changeSet: { id: "t2" } as never, fromPending: false }} />);
        await waitFor(() => expect(resolve).toHaveBeenCalledWith(1, { turnId: "t2", outcome: "stale", fromPending: false }));
    });

    it("proposes in review mode and maps the proposal outcome", async () => {
        handle.propose.mockReturnValue({ status: "staged" });
        const resolve = vi.spyOn(c, "resolveChangeSet").mockResolvedValue(undefined);
        render(<WorkflowTab {...base} composition={{ ...base.composition, applyMode: "review" }}
            pending={{ turnId: "t3", changeSet: { id: "t3" } as never, fromPending: false }} />);
        await waitFor(() => expect(handle.propose).toHaveBeenCalled());
        act(() => editorProps.onProposalResolved("t3", "accepted"));
        await waitFor(() => expect(resolve).toHaveBeenCalledWith(1, { turnId: "t3", outcome: "applied", fromPending: false }));
    });

    it("saves agent-origin changes with the turn id after the debounce", async () => {
        handle.apply.mockReturnValue({ status: "applied" });
        vi.spyOn(c, "resolveChangeSet").mockResolvedValue(undefined);
        const save = vi.spyOn(c, "saveItem").mockResolvedValue({ ...base.item, contentRevision: "r1" });
        render(<WorkflowTab {...base} pending={{ turnId: "t4", changeSet: { id: "t4" } as never, fromPending: false }} />);
        await waitFor(() => expect(handle.apply).toHaveBeenCalled());
        const next = { id: "w", name: "wf", nodes: [{ id: "n" }], edges: [] };
        act(() => editorProps.onChange(next, { contentRevision: "x", origin: "agent:axiom-workbench" }));
        await waitFor(() => expect(save).toHaveBeenCalledWith(1, 10, { content: next, baseRevision: "r0", turnId: "t4" }), { timeout: 2000 });
    });

    it("offers a reload on a 409 save", async () => {
        vi.spyOn(c, "saveItem").mockRejectedValue(new c.ApiError("stale", 409, { currentRevision: "r9" }));
        const { findByRole } = render(<WorkflowTab {...base} pending={null} />);
        act(() => editorProps.onChange({ id: "w", name: "wf", nodes: [], edges: [] }, { contentRevision: "x", origin: "user" }));
        expect(await findByRole("button", { name: /reload/i }, { timeout: 2000 })).toBeTruthy();
    });

    it("context actions prefill the chat and disable during AI turns", () => {
        render(<WorkflowTab {...base} pending={null} composition={{ ...base.composition, turnState: "ai-working" }} />);
        const actions = editorProps.spi.contextActions({ target: { kind: "node", nodeId: "a" }, readOnly: false,
            workflow: { nodes: [{ id: "a", name: "A", type: "action" }], edges: [] } });
        expect(actions[0].disabled).toBe(true);
        actions[0].onSelect({ target: { kind: "node", nodeId: "a" }, workflow: { nodes: [{ id: "a", name: "A", type: "action" }], edges: [] } });
        expect(base.onAskAi).toHaveBeenCalledWith("About node `a` (A, action): ");
    });
});
