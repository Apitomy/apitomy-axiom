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

    it("reloads after an SSE reconnect through the debounce timer", () => {
        const reload = vi.fn();
        renderHook(() => useWidgetRefresh(["activity"], reload));
        act(() => { reconnects.forEach((cb) => cb()); emit("activity"); });
        expect(reload).not.toHaveBeenCalled();
        act(() => { vi.advanceTimersByTime(2000); });
        expect(reload).toHaveBeenCalledTimes(1);
    });

    it("forces a reload after maxWaitMs under continuous events", () => {
        const reload = vi.fn();
        renderHook(() => useWidgetRefresh(["activity"], reload, { debounceMs: 2000, maxWaitMs: 10000 }));
        // An event every second for 25 s: the quiet period never happens.
        for (let i = 0; i < 25; i++) {
            act(() => { emit("activity"); vi.advanceTimersByTime(1000); });
        }
        expect(reload).toHaveBeenCalledTimes(2);
        act(() => { vi.advanceTimersByTime(2000); });
        expect(reload).toHaveBeenCalledTimes(3);
    });

    it("uses a 10 second default max wait", () => {
        const reload = vi.fn();
        renderHook(() => useWidgetRefresh(["activity"], reload));
        for (let i = 0; i < 10; i++) {
            act(() => { emit("activity"); vi.advanceTimersByTime(1000); });
        }
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
