import { useEffect, useRef } from "react";
import { sseClient } from "../config/sse";

/** Options for {@link useWidgetRefresh}. */
export interface WidgetRefreshOptions {
    /** Quiet period after the last matching event before reloading (default 2000 ms). */
    debounceMs?: number;
    /**
     * Longest time a reload may be postponed by a continuous stream of events (default 10000 ms). When
     * exceeded, the reload runs even though events are still arriving.
     */
    maxWaitMs?: number;
}

/** Default debounce for live widget refreshes. */
export const DEFAULT_WIDGET_REFRESH_DEBOUNCE_MS = 2000;

/** Default maximum wait for live widget refreshes under continuous events. */
export const DEFAULT_WIDGET_REFRESH_MAX_WAIT_MS = 10000;

/**
 * Calls `reload` (trailing-edge debounce with a maximum wait) whenever an SSE event with one of the given names arrives.
 *
 * Built on the shared {@link sseClient}; does nothing when `eventNames` is empty or undefined. The latest
 * `reload` callback is always used, so callers do not need to memoise it. `reload` is also called right after
 * the SSE connection is re-established, since events may have been missed while disconnected
 * (through the same debounce timer).
 *
 * @param eventNames SSE event names (e.g. "workflow-updated", "inbox-updated", "activity")
 * @param reload the callback to run after matching events
 * @param options debounce options
 */
export function useWidgetRefresh(
    eventNames: readonly string[] | undefined,
    reload: () => void,
    options: WidgetRefreshOptions = {}
): void {
    const reloadRef = useRef(reload);
    reloadRef.current = reload;
    const debounceMs = options.debounceMs ?? DEFAULT_WIDGET_REFRESH_DEBOUNCE_MS;
    const maxWaitMs = options.maxWaitMs ?? DEFAULT_WIDGET_REFRESH_MAX_WAIT_MS;
    const key = (eventNames ?? []).join(",");

    useEffect(() => {
        if (!key) return undefined;
        const names = new Set(key.split(","));
        let timer: ReturnType<typeof setTimeout> | undefined;
        /** Time of the first event not yet followed by a reload. */
        let firstPendingAt: number | undefined;

        const fire = (): void => {
            timer = undefined;
            firstPendingAt = undefined;
            reloadRef.current();
        };
        const schedule = (): void => {
            const now = Date.now();
            if (firstPendingAt === undefined) firstPendingAt = now;
            if (timer) clearTimeout(timer);
            const untilMax = firstPendingAt + maxWaitMs - now;
            timer = setTimeout(fire, Math.max(0, Math.min(debounceMs, untilMax)));
        };

        const unsubscribe = sseClient.subscribe((event) => {
            if (names.has(event.type)) schedule();
        });
        // Events may have been missed while disconnected: reload after a reconnect.
        const unsubscribeReconnect = sseClient.onReconnect(schedule);
        return () => {
            if (timer) clearTimeout(timer);
            unsubscribe();
            unsubscribeReconnect();
        };
    }, [key, debounceMs, maxWaitMs]);
}
