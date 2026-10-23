import { renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../config/api";
import { clearEventSchemaCache, useEventSchema, useEventSchemas } from "./useEventSchemas";

vi.mock("../config/api", () => ({
    fetchEventSchemas: vi.fn(),
    fetchEventSchema: vi.fn(),
}));

const SUMMARIES = [{ type: "issue.closed", description: "Closed" }, { type: "issue.created", description: "Created" }];

beforeEach(() => {
    clearEventSchemaCache();
    vi.mocked(api.fetchEventSchemas).mockReset().mockResolvedValue(SUMMARIES);
    vi.mocked(api.fetchEventSchema).mockReset()
        .mockImplementation(async (type: string) => ({ title: type, type: "object" }));
});

describe("useEventSchemas", () => {
    it("loads the type list once per session", async () => {
        const first = renderHook(() => useEventSchemas());
        await waitFor(() => expect(first.result.current.data).toEqual(SUMMARIES));
        expect(first.result.current.loading).toBe(false);
        const second = renderHook(() => useEventSchemas());
        await waitFor(() => expect(second.result.current.data).toEqual(SUMMARIES));
        expect(api.fetchEventSchemas).toHaveBeenCalledTimes(1);
    });

    it("reports errors and retries on the next mount", async () => {
        vi.mocked(api.fetchEventSchemas).mockRejectedValueOnce(new Error("boom"));
        const failed = renderHook(() => useEventSchemas());
        await waitFor(() => expect(failed.result.current.error).toBe("boom"));
        failed.unmount();
        const retried = renderHook(() => useEventSchemas());
        await waitFor(() => expect(retried.result.current.data).toEqual(SUMMARIES));
        expect(api.fetchEventSchemas).toHaveBeenCalledTimes(2);
    });
});

describe("useEventSchema", () => {
    it("caches each type's schema and refetches only for new types", async () => {
        const { result, rerender } = renderHook(({ type }: { type: string }) => useEventSchema(type),
            { initialProps: { type: "issue.created" } });
        await waitFor(() => expect(result.current.data?.title).toBe("issue.created"));
        rerender({ type: "issue.closed" });
        await waitFor(() => expect(result.current.data?.title).toBe("issue.closed"));
        rerender({ type: "issue.created" });
        await waitFor(() => expect(result.current.data?.title).toBe("issue.created"));
        expect(api.fetchEventSchema).toHaveBeenCalledTimes(2);
    });

    it("returns no data and does not fetch when the type is undefined", () => {
        const { result } = renderHook(() => useEventSchema(undefined));
        expect(result.current).toEqual({ data: undefined, loading: false, error: undefined });
        expect(api.fetchEventSchema).not.toHaveBeenCalled();
    });
});
