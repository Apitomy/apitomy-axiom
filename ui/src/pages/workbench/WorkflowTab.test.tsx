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

    it("edits properties in a dialog and hides simulation", () => {
        render(<WorkflowTab {...base} pending={null} />);
        expect(editorProps.propertiesDisplay).toBe("dialog");
        expect(editorProps.features).toEqual({ simulate: false });
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

    it("tags only the apply-triggered change with the turn id", async () => {
        handle.apply.mockReturnValue({ status: "applied" });
        vi.spyOn(c, "resolveChangeSet").mockResolvedValue(undefined);
        const save = vi.spyOn(c, "saveItem").mockResolvedValue({ ...base.item, contentRevision: "r1" });
        render(<WorkflowTab {...base} pending={{ turnId: "t5", changeSet: { id: "t5" } as never, fromPending: false }} />);
        await waitFor(() => expect(handle.apply).toHaveBeenCalled());
        const first = { id: "w", name: "wf", nodes: [{ id: "a" }], edges: [] };
        act(() => editorProps.onChange(first, { contentRevision: "x1", origin: "agent:axiom-workbench" }));
        await waitFor(() => expect(save).toHaveBeenCalledTimes(1), { timeout: 2000 });
        expect(save.mock.calls[0][2].turnId).toBe("t5");
        const second = { id: "w", name: "wf", nodes: [], edges: [] };
        act(() => editorProps.onChange(second, { contentRevision: "x2", origin: "agent:axiom-workbench" }));
        await waitFor(() => expect(save).toHaveBeenCalledTimes(2), { timeout: 2000 });
        expect(save.mock.calls[1][2]).toEqual({ content: second, baseRevision: "r1" });
    });

    it("resolves as rejected when apply throws", async () => {
        handle.apply.mockImplementation(() => { throw new Error("boom"); });
        const resolve = vi.spyOn(c, "resolveChangeSet").mockResolvedValue(undefined);
        const { findByText } = render(<WorkflowTab {...base}
            pending={{ turnId: "t6", changeSet: { id: "t6" } as never, fromPending: true }} />);
        await waitFor(() => expect(resolve).toHaveBeenCalledWith(1, { turnId: "t6", outcome: "rejected", fromPending: true }));
        expect(base.onPendingHandled).toHaveBeenCalled();
        expect(await findByText(/could not be applied: boom/)).toBeTruthy();
    });

    it("withdraws and rejects a staged proposal on unmount", async () => {
        handle.propose.mockReturnValue({ status: "staged" });
        const resolve = vi.spyOn(c, "resolveChangeSet").mockResolvedValue(undefined);
        const { unmount } = render(<WorkflowTab {...base} composition={{ ...base.composition, applyMode: "review" }}
            pending={{ turnId: "t7", changeSet: { id: "cs7" } as never, fromPending: false }} />);
        await waitFor(() => expect(handle.propose).toHaveBeenCalled());
        unmount();
        expect(handle.withdraw).toHaveBeenCalledWith("cs7");
        await waitFor(() => expect(resolve).toHaveBeenCalledWith(1, { turnId: "t7", outcome: "rejected", fromPending: false }));
    });

    it("resolves a staged proposal once when withdraw reports it synchronously on unmount", async () => {
        handle.propose.mockReturnValue({ status: "staged" });
        handle.withdraw.mockImplementation((id: string) => editorProps.onProposalResolved(id, "withdrawn"));
        const resolve = vi.spyOn(c, "resolveChangeSet").mockResolvedValue(undefined);
        const { unmount } = render(<WorkflowTab {...base} composition={{ ...base.composition, applyMode: "review" }}
            pending={{ turnId: "t8", changeSet: { id: "cs8" } as never, fromPending: false }} />);
        await waitFor(() => expect(handle.propose).toHaveBeenCalled());
        unmount();
        await waitFor(() => expect(resolve).toHaveBeenCalledTimes(1));
        expect(resolve).toHaveBeenCalledWith(1, { turnId: "t8", outcome: "rejected", fromPending: false });
    });

    it("drops the expected agent turn when a user change arrives first", async () => {
        handle.propose.mockReturnValue({ status: "staged" });
        vi.spyOn(c, "resolveChangeSet").mockResolvedValue(undefined);
        const save = vi.spyOn(c, "saveItem").mockResolvedValue({ ...base.item, contentRevision: "r1" });
        render(<WorkflowTab {...base} composition={{ ...base.composition, applyMode: "review" }}
            pending={{ turnId: "t9", changeSet: { id: "cs9" } as never, fromPending: false }} />);
        await waitFor(() => expect(handle.propose).toHaveBeenCalled());
        act(() => editorProps.onProposalResolved("cs9", "accepted"));
        const user = { id: "w", name: "wf", nodes: [{ id: "u" }], edges: [] };
        act(() => editorProps.onChange(user, { contentRevision: "x1", origin: "user" }));
        await waitFor(() => expect(save).toHaveBeenCalledTimes(1), { timeout: 2000 });
        expect(save.mock.calls[0][2]).toEqual({ content: user, baseRevision: "r0" });
        const agent = { id: "w", name: "wf", nodes: [], edges: [] };
        act(() => editorProps.onChange(agent, { contentRevision: "x2", origin: "agent:axiom-workbench" }));
        await waitFor(() => expect(save).toHaveBeenCalledTimes(2), { timeout: 2000 });
        expect(save.mock.calls[1][2]).toEqual({ content: agent, baseRevision: "r1" });
    });

});
