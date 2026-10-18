import { renderHook, act } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { SseListener } from "../config/sse";

const listeners: SseListener[] = [];
const reconnects: (() => void)[] = [];
vi.mock("../config/sse", () => ({
    sseClient: {
        subscribe: vi.fn((l: SseListener) => {
            listeners.push(l);
            return () => { listeners.splice(listeners.indexOf(l), 1); };
        }),
        onReconnect: vi.fn((cb: () => void) => {
            reconnects.push(cb);
            return () => { reconnects.splice(reconnects.indexOf(cb), 1); };
        }),
    },
}));

import { useWidgetRefresh } from "./useWidgetRefresh";

function emit(type: string): void {
    [...listeners].forEach((l) => l({ type, data: {} }));
}

describe("useWidgetRefresh", () => {
    beforeEach(() => { vi.useFakeTimers(); listeners.length = 0; reconnects.length = 0; });
    afterEach(() => { vi.useRealTimers(); });

    it("calls reload once, debounced, for matching events only", () => {
        const reload = vi.fn();
        renderHook(() => useWidgetRefresh(["workflow-updated"], reload, { debounceMs: 2000 }));
        act(() => { emit("workflow-updated"); emit("other"); emit("workflow-updated"); });
        expect(reload).not.toHaveBeenCalled();
        act(() => { vi.advanceTimersByTime(1999); });
        expect(reload).not.toHaveBeenCalled();
        act(() => { vi.advanceTimersByTime(1); });
        expect(reload).toHaveBeenCalledTimes(1);
    });

    it("ignores non-matching events and does not subscribe without event names", () => {
        const reload = vi.fn();
        renderHook(() => useWidgetRefresh(undefined, reload));
        expect(listeners).toHaveLength(0);
        renderHook(() => useWidgetRefresh(["inbox-updated"], reload));
        act(() => { emit("activity"); vi.advanceTimersByTime(5000); });
        expect(reload).not.toHaveBeenCalled();
    });

    it("uses a 2 second default debounce and the latest reload callback", () => {
        const first = vi.fn();
        const second = vi.fn();
        const { rerender } = renderHook(({ cb }) => useWidgetRefresh(["activity"], cb), {
            initialProps: { cb: first },
        });
        rerender({ cb: second });
        act(() => { emit("activity"); vi.advanceTimersByTime(2000); });
        expect(first).not.toHaveBeenCalled();
        expect(second).toHaveBeenCalledTimes(1);
    });

    it("reloads immediately after an SSE reconnect", () => {
        const reload = vi.fn();
        renderHook(() => useWidgetRefresh(["activity"], reload));
        act(() => { reconnects.forEach((cb) => cb()); });
        expect(reload).toHaveBeenCalledTimes(1);
    });

    it("unsubscribes and cancels pending reloads on unmount", () => {
        const reload = vi.fn();
        const { unmount } = renderHook(() => useWidgetRefresh(["activity"], reload));
        act(() => { emit("activity"); });
        unmount();
        act(() => { vi.advanceTimersByTime(5000); });
        expect(reload).not.toHaveBeenCalled();
        expect(listeners).toHaveLength(0);
    });
});
