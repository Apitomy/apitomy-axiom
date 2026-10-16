import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";

type Listener = (e: { type: string; data: Record<string, unknown> }) => void;
const listeners: Listener[] = [];
vi.mock("../../config/sse", () => ({
    sseClient: { subscribe: (l: Listener) => { listeners.push(l); return () => listeners.splice(listeners.indexOf(l), 1); } },
}));
const fetchWorkflowRuns = vi.fn();
vi.mock("../../config/api", () => ({ fetchWorkflowRuns: (...a: unknown[]) => fetchWorkflowRuns(...a) }));

import { useWorkflowRuns } from "./useWorkflowRuns";

const run = { runId: 7, status: "failed", title: "T", definitionVersion: 2, startedOn: "2026-10-14T10:00:00Z" };

describe("useWorkflowRuns", () => {
    beforeEach(() => {
        fetchWorkflowRuns.mockReset();
        fetchWorkflowRuns.mockResolvedValue({ items: [run], totalCount: 1 });
        listeners.length = 0;
    });
    afterEach(() => vi.useRealTimers());

    it("loads the runs of the definition", async () => {
        const { result } = renderHook(() => useWorkflowRuns(5));
        await waitFor(() => expect(result.current.runs).toHaveLength(1));
        expect(fetchWorkflowRuns).toHaveBeenCalledWith(1, 50, { definitionId: 5 });
        expect(result.current.loading).toBe(false);
        expect(result.current.error).toBeNull();
    });

    it("loads nothing without a definition", () => {
        const { result } = renderHook(() => useWorkflowRuns(null));
        expect(fetchWorkflowRuns).not.toHaveBeenCalled();
        expect(result.current.runs).toEqual([]);
    });

    it("exposes load errors", async () => {
        fetchWorkflowRuns.mockRejectedValue(new Error("boom"));
        const { result } = renderHook(() => useWorkflowRuns(5));
        await waitFor(() => expect(result.current.error).toBe("boom"));
    });

    it("refetches (debounced) on workflow-updated events", async () => {
        const { result } = renderHook(() => useWorkflowRuns(5));
        await waitFor(() => expect(result.current.runs).toHaveLength(1));
        vi.useFakeTimers();
        act(() => {
            listeners.forEach((l) => l({ type: "workflow-updated", data: { subjectId: 1 } }));
            listeners.forEach((l) => l({ type: "workflow-updated", data: { subjectId: 1 } }));
            listeners.forEach((l) => l({ type: "other", data: {} }));
        });
        expect(fetchWorkflowRuns).toHaveBeenCalledTimes(1);
        await act(async () => { vi.advanceTimersByTime(500); });
        expect(fetchWorkflowRuns).toHaveBeenCalledTimes(2);
    });

    it("ignores out-of-order responses", async () => {
        let resolveFirst: (v: unknown) => void = () => undefined;
        fetchWorkflowRuns.mockReset()
            .mockReturnValueOnce(new Promise((r) => { resolveFirst = r; }))
            .mockResolvedValueOnce({ items: [{ ...run, runId: 8 }], totalCount: 1 });
        const { result } = renderHook(() => useWorkflowRuns(5));
        act(() => result.current.refresh());
        await waitFor(() => expect(result.current.runs[0]?.runId).toBe(8));
        await act(async () => { resolveFirst({ items: [{ ...run, runId: 1 }], totalCount: 1 }); });
        expect(result.current.runs[0]?.runId).toBe(8);
    });
});
