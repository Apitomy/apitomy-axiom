import { renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { usePolling } from "./usePolling";

function setHidden(hidden: boolean): void {
    Object.defineProperty(document, "hidden", { configurable: true, get: () => hidden });
}

describe("usePolling", () => {
    beforeEach(() => { vi.useFakeTimers(); setHidden(false); });
    afterEach(() => { vi.useRealTimers(); setHidden(false); });

    it("calls fn on each interval while visible", () => {
        const fn = vi.fn();
        renderHook(() => usePolling(fn, 1000));
        vi.advanceTimersByTime(3000);
        expect(fn).toHaveBeenCalledTimes(3);
    });

    it("skips ticks while hidden and refreshes once when visible again", () => {
        const fn = vi.fn();
        renderHook(() => usePolling(fn, 1000));
        setHidden(true);
        document.dispatchEvent(new Event("visibilitychange"));
        vi.advanceTimersByTime(5000);
        expect(fn).not.toHaveBeenCalled();
        setHidden(false);
        document.dispatchEvent(new Event("visibilitychange"));
        expect(fn).toHaveBeenCalledTimes(1);
    });

    it("stops after unmount", () => {
        const fn = vi.fn();
        const { unmount } = renderHook(() => usePolling(fn, 1000));
        unmount();
        vi.advanceTimersByTime(3000);
        document.dispatchEvent(new Event("visibilitychange"));
        expect(fn).not.toHaveBeenCalled();
    });
});
