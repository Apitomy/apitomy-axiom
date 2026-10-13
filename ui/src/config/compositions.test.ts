import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, mergeTestRun, mergeTestRuns, resolveChangeSet, saveItem, subscribeComposition } from "./compositions";
import type { CompositionTestRun, TestRunStatus } from "./compositions";
import { sseClient } from "./sse";

function mockFetch(status: number, body: unknown) {
    return vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(status === 204 ? null : JSON.stringify(body), {
        status, headers: { "Content-Type": "application/json" },
    }));
}

describe("compositions api", () => {
    afterEach(() => vi.restoreAllMocks());

    it("saves an item with base revision and turn id", async () => {
        const fetchSpy = mockFetch(200, { id: 2, name: "t", type: "tool", contentRevision: "sha256:b", lastAuthor: "ai", changed: true });
        await saveItem(1, 2, { content: { name: "t" }, baseRevision: "sha256:a", turnId: "turn-1" });
        const [url, init] = fetchSpy.mock.calls[0];
        expect(String(url)).toMatch(/\/api\/v1\/compositions\/1\/items\/2$/);
        expect((init as RequestInit).method).toBe("PUT");
        expect(JSON.parse(String((init as RequestInit).body))).toEqual({ content: { name: "t" }, baseRevision: "sha256:a", turnId: "turn-1" });
    });

    it("throws ApiError with status and body on 409", async () => {
        mockFetch(409, { message: "stale", currentRevision: "sha256:z" });
        const error = await saveItem(1, 2, { content: {}, baseRevision: "x" }).catch((e) => e);
        expect(error).toBeInstanceOf(ApiError);
        expect(error.status).toBe(409);
        expect((error.body as { currentRevision: string }).currentRevision).toBe("sha256:z");
    });

    it("posts resolutions with fromPending", async () => {
        const fetchSpy = mockFetch(204, {});
        await resolveChangeSet(1, { turnId: "t", outcome: "stale", fromPending: true });
        expect(JSON.parse(String((fetchSpy.mock.calls[0][1] as RequestInit).body)))
            .toEqual({ turnId: "t", outcome: "stale", fromPending: true });
    });

    it("routes SSE events by composition", () => {
        let listener: ((e: { type: string; data: Record<string, unknown> }) => void) | undefined;
        vi.spyOn(sseClient, "subscribe").mockImplementation((l) => { listener = l; return () => {}; });
        const changeSet = vi.fn();
        subscribeComposition(5, { changeSet });
        listener!({ type: "composition-changeset", data: { compositionId: 6, turnId: "x", changeSet: {} } });
        listener!({ type: "composition-changeset", data: { compositionId: 5, turnId: "y", changeSet: { id: "y" } } });
        expect(changeSet).toHaveBeenCalledTimes(1);
        expect(changeSet).toHaveBeenCalledWith({ compositionId: 5, turnId: "y", changeSet: { id: "y" } });
    });
});

describe("mergeTestRun keeps finished runs", () => {
    const r = (id: string, status: TestRunStatus): CompositionTestRun => ({
        id, itemId: 1, itemType: "tool", itemName: "t", author: "user", status, startedAt: "",
    });

    it("never replaces a finished run with a running one", () => {
        expect(mergeTestRun([r("a", "passed")], r("a", "running"))[0].status).toBe("passed");
        expect(mergeTestRun([r("a", "running")], r("a", "failed"))[0].status).toBe("failed");
    });

    it("mergeTestRuns keeps runs already in state and prefers finished ones", () => {
        const merged = mergeTestRuns([r("live", "running"), r("b", "passed")], [r("b", "running"), r("old", "passed")]);
        expect(merged.map((x) => `${x.id}:${x.status}`)).toEqual(["live:running", "b:passed", "old:passed"]);
    });
});
