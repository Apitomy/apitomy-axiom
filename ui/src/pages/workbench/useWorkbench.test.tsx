import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useWorkbench } from "./useWorkbench";
import * as c from "../../config/compositions";

let sse: Parameters<typeof c.subscribeComposition>[1] = {};

beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(c, "fetchComposition").mockResolvedValue({ id: 1, name: "C", turnState: "idle", applyMode: "live",
        createdAt: "", updatedAt: "", pendingTurnId: "t0", pendingChangeSet: { id: "t0", baseRevision: "r", author: "agent:axiom-workbench", summary: "s", ops: [] } } as never);
    vi.spyOn(c, "listItems").mockResolvedValue([{ id: 10, type: "workflow", name: "wf", contentRevision: "r", lastAuthor: "user", changed: true }]);
    vi.spyOn(c, "fetchItem").mockResolvedValue({ id: 10, type: "workflow", name: "wf", contentRevision: "r", lastAuthor: "user",
        changed: true, content: { id: "w", name: "wf", nodes: [], edges: [] } } as never);
    vi.spyOn(c, "ensureCompositionSession").mockResolvedValue({ id: 1, assistantSessionId: "s1" } as never);
    vi.spyOn(c, "validateComposition").mockResolvedValue({ items: [{ itemId: 10, type: "workflow", name: "wf", valid: true, problems: [] }] });
    vi.spyOn(c, "subscribeComposition").mockImplementation((_id, handlers) => { sse = handlers; return () => {}; });
});

describe("useWorkbench", () => {
    it("loads, ensures the session and adopts a stored pending change set", async () => {
        const { result } = renderHook(() => useWorkbench(1));
        await waitFor(() => expect(result.current.contents[10]).toBeTruthy());
        expect(c.ensureCompositionSession).toHaveBeenCalledWith(1);
        await waitFor(() => expect(result.current.pending).toEqual(expect.objectContaining({ turnId: "t0", fromPending: true })));
        await waitFor(() => expect(result.current.problems[10]).toEqual([]));
    });

    it("takes live change sets from SSE and records completed turns", async () => {
        const { result } = renderHook(() => useWorkbench(1));
        await waitFor(() => expect(result.current.contents[10]).toBeTruthy());
        await waitFor(() => expect(result.current.pending).not.toBeNull());
        act(() => result.current.markPendingHandled());
        act(() => sse.changeSet?.({ compositionId: 1, turnId: "t1", changeSet: { id: "t1" } as never }));
        expect(result.current.pending).toEqual(expect.objectContaining({ turnId: "t1", fromPending: false }));
        await act(async () => sse.turn?.({ compositionId: 1, turnId: "t1", state: "idle" }));
        expect(result.current.lastCompletedTurnId).toBe("t1");
    });

    it("ignores an SSE change set for a turn already handled", async () => {
        const { result } = renderHook(() => useWorkbench(1));
        await waitFor(() => expect(result.current.pending?.turnId).toBe("t0"));
        act(() => result.current.markPendingHandled());
        act(() => sse.changeSet?.({ compositionId: 1, turnId: "t0", changeSet: { id: "t0" } as never }));
        expect(result.current.pending).toBeNull();
    });

    it("keeps fromPending when SSE repeats the adopted pending turn", async () => {
        const { result } = renderHook(() => useWorkbench(1));
        await waitFor(() => expect(result.current.pending?.turnId).toBe("t0"));
        act(() => sse.changeSet?.({ compositionId: 1, turnId: "t0", changeSet: { id: "t0" } as never }));
        expect(result.current.pending).toEqual(expect.objectContaining({ turnId: "t0", fromPending: true }));
    });

    it("reports a session start failure without blocking", async () => {
        vi.spyOn(c, "ensureCompositionSession").mockRejectedValue(new c.ApiError("agent down", 503, {}));
        const { result } = renderHook(() => useWorkbench(1));
        await waitFor(() => expect(result.current.sessionError).toContain("agent down"));
        expect(result.current.items).toHaveLength(1);
    });
});
