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
    vi.spyOn(c, "listTestRuns").mockResolvedValue([]);
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

    it("validates without waiting for the session to start", async () => {
        vi.spyOn(c, "ensureCompositionSession").mockReturnValue(new Promise(() => {}));
        vi.spyOn(c, "validateComposition").mockResolvedValue({ items: [{ itemId: 10, type: "workflow", name: "wf",
            valid: false, problems: [{ severity: "error", message: "bad" }] }] } as never);
        const { result } = renderHook(() => useWorkbench(1));
        await waitFor(() => expect(result.current.problems[10]).toHaveLength(1));
        expect(result.current.composition?.assistantSessionId).toBeUndefined();
    });

    it("loads every action type's content so drafts reach the catalog", async () => {
        vi.spyOn(c, "listItems").mockResolvedValue([
            { id: 10, type: "workflow", name: "wf", contentRevision: "r", lastAuthor: "user", changed: true },
            { id: 11, type: "action-type", name: "at", contentRevision: "a1", lastAuthor: "ai", changed: true },
        ]);
        vi.spyOn(c, "fetchItem").mockImplementation(async (_cid, itemId) => (itemId === 11
            ? { id: 11, type: "action-type", name: "at", contentRevision: "a1", lastAuthor: "ai", changed: true,
                content: { name: "at" } }
            : { id: 10, type: "workflow", name: "wf", contentRevision: "r", lastAuthor: "user", changed: true,
                content: { id: "w", name: "wf", nodes: [], edges: [] } }) as never);
        const { result } = renderHook(() => useWorkbench(1));
        await waitFor(() => expect(result.current.contents[11]).toEqual({ name: "at" }));

        vi.spyOn(c, "listItems").mockResolvedValue([
            { id: 10, type: "workflow", name: "wf", contentRevision: "r", lastAuthor: "user", changed: true },
            { id: 11, type: "action-type", name: "at", contentRevision: "a1", lastAuthor: "ai", changed: true },
            { id: 12, type: "action-type", name: "new", contentRevision: "n1", lastAuthor: "ai", changed: true },
        ]);
        vi.spyOn(c, "fetchItem").mockResolvedValue({ id: 12, type: "action-type", name: "new", contentRevision: "n1",
            lastAuthor: "ai", changed: true, content: { name: "new" } } as never);
        await act(async () => sse.itemChanged?.({ compositionId: 1, itemId: 12, contentRevision: "n1" } as never));
        await waitFor(() => expect(result.current.contents[12]).toEqual({ name: "new" }));
    });
});

const run = (id: string, status: c.TestRunStatus): c.CompositionTestRun => ({
    id, itemId: 11, itemType: "action-type", itemName: "greet", author: "ai", status, startedAt: "",
});

describe("test runs", () => {
    it("loads runs and merges SSE updates by id, newest first", async () => {
        vi.spyOn(c, "listTestRuns").mockResolvedValue([run("a", "passed")]);
        const { result } = renderHook(() => useWorkbench(1));
        await waitFor(() => expect(result.current.testRuns).toHaveLength(1));
        act(() => sse.testRun?.({ compositionId: 1, run: run("b", "running") }));
        expect(result.current.testRuns.map((r) => r.id)).toEqual(["b", "a"]);
        act(() => sse.testRun?.({ compositionId: 1, run: run("b", "failed") }));
        expect(result.current.testRuns.map((r) => `${r.id}:${r.status}`)).toEqual(["b:failed", "a:passed"]);
    });

    it("runs a test and keeps its result", async () => {
        const test = vi.spyOn(c, "testItem").mockResolvedValue(run("c", "passed"));
        const { result } = renderHook(() => useWorkbench(1));
        await waitFor(() => expect(result.current.items).toHaveLength(1));
        await act(async () => { await result.current.runTest(11, { who: "bob" }, 5); });
        expect(test).toHaveBeenCalledWith(1, 11, { inputs: { who: "bob" }, subjectId: 5 });
        expect(result.current.testRuns[0].id).toBe("c");
    });

    it("shows a running placeholder for a run id at once and replaces it with the result", async () => {
        vi.spyOn(c, "listItems").mockResolvedValue([{ id: 11, type: "tool", name: "t", contentRevision: "r",
            lastAuthor: "user", changed: true }]);
        let finish: (r: c.CompositionTestRun) => void = () => {};
        const test = vi.spyOn(c, "testItem").mockReturnValue(new Promise((resolve) => { finish = resolve; }));
        const { result } = renderHook(() => useWorkbench(1));
        await waitFor(() => expect(result.current.items).toHaveLength(1));
        let done: Promise<c.CompositionTestRun> = Promise.resolve({} as never);
        act(() => { done = result.current.runTest(11, { a: 1 }, undefined, "client-run-1"); });
        expect(test).toHaveBeenCalledWith(1, 11, { inputs: { a: 1 }, runId: "client-run-1" });
        expect(result.current.testRuns[0]).toEqual(expect.objectContaining({ id: "client-run-1", status: "running",
            itemName: "t" }));
        await act(async () => { finish({ ...run("client-run-1", "passed"), itemId: 11 }); await done; });
        expect(result.current.testRuns.filter((r) => r.id === "client-run-1")).toHaveLength(1);
        expect(result.current.testRuns[0].status).toBe("passed");
    });

    it("drops the placeholder when the run request fails", async () => {
        vi.spyOn(c, "listItems").mockResolvedValue([{ id: 11, type: "tool", name: "t", contentRevision: "r",
            lastAuthor: "user", changed: true }]);
        vi.spyOn(c, "testItem").mockRejectedValue(new Error("busy"));
        const { result } = renderHook(() => useWorkbench(1));
        await waitFor(() => expect(result.current.items).toHaveLength(1));
        await act(async () => {
            await expect(result.current.runTest(11, {}, undefined, "client-run-2")).rejects.toThrow("busy");
        });
        expect(result.current.testRuns.find((r) => r.id === "client-run-2")).toBeUndefined();
    });
});

describe("test runs during load", () => {
    it("keeps runs that start while the history loads", async () => {
        let resolve: (runs: c.CompositionTestRun[]) => void = () => {};
        vi.spyOn(c, "listTestRuns").mockReturnValue(new Promise((r) => { resolve = r; }));
        const { result } = renderHook(() => useWorkbench(1));
        await waitFor(() => expect(c.listTestRuns).toHaveBeenCalled());
        act(() => sse.testRun?.({ compositionId: 1, run: run("live", "running") }));
        await act(async () => { resolve([run("old", "passed")]); });
        expect(result.current.testRuns.map((r) => r.id)).toEqual(["live", "old"]);
    });

    it("clears runs when the composition changes", async () => {
        vi.spyOn(c, "listTestRuns").mockResolvedValueOnce([run("a", "passed")])
            .mockReturnValue(new Promise(() => {}));
        const { result, rerender } = renderHook(({ id }) => useWorkbench(id), { initialProps: { id: 1 } });
        await waitFor(() => expect(result.current.testRuns).toHaveLength(1));
        rerender({ id: 2 });
        expect(result.current.testRuns).toEqual([]);
        expect(result.current.testRunsLoaded).toBe(false);
    });

    it("reports when the run history has loaded, also on failure", async () => {
        let resolve: (runs: c.CompositionTestRun[]) => void = () => {};
        vi.spyOn(c, "listTestRuns").mockReturnValueOnce(new Promise((r) => { resolve = r; }))
            .mockRejectedValueOnce(new Error("x"));
        const { result, rerender } = renderHook(({ id }) => useWorkbench(id), { initialProps: { id: 1 } });
        await waitFor(() => expect(c.listTestRuns).toHaveBeenCalled());
        expect(result.current.testRunsLoaded).toBe(false);
        await act(async () => { resolve([run("a", "passed")]); });
        expect(result.current.testRunsLoaded).toBe(true);
        rerender({ id: 2 });
        expect(result.current.testRunsLoaded).toBe(false);
        await waitFor(() => expect(result.current.testRunsLoaded).toBe(true));
    });
});

describe("mergeTestRun", () => {
    it("caps the list at 50", () => {
        const many = Array.from({ length: 50 }, (_v, i) => run(String(i), "passed"));
        expect(c.mergeTestRun(many, run("new", "running"))).toHaveLength(50);
        expect(c.mergeTestRun(many, run("new", "running"))[0].id).toBe("new");
    });
});
