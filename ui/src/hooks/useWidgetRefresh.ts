import { useEffect, useRef } from "react";
import { sseClient } from "../config/sse";

/** Options for {@link useWidgetRefresh}. */
export interface WidgetRefreshOptions {
    /** Quiet period after the last matching event before reloading (default 2000 ms). */
    debounceMs?: number;
}

/** Default debounce for live widget refreshes. */
export const DEFAULT_WIDGET_REFRESH_DEBOUNCE_MS = 2000;

/**
 * Calls `reload` (debounced) whenever an SSE event with one of the given names arrives.
 *
 * Built on the shared {@link sseClient}; does nothing when `eventNames` is empty or undefined. The latest
 * `reload` callback is always used, so callers do not need to memoise it. `reload` is also called right after
 * the SSE connection is re-established, since events may have been missed while disconnected.
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
    const key = (eventNames ?? []).join(",");

    useEffect(() => {
        if (!key) return undefined;
        const names = new Set(key.split(","));
        let timer: ReturnType<typeof setTimeout> | undefined;
        const unsubscribe = sseClient.subscribe((event) => {
            if (!names.has(event.type)) return;
            if (timer) clearTimeout(timer);
            timer = setTimeout(() => {
                timer = undefined;
                reloadRef.current();
            }, debounceMs);
        });
        // Events may have been missed while disconnected: reload after a reconnect.
        const unsubscribeReconnect = sseClient.onReconnect(() => reloadRef.current());
        return () => {
            if (timer) clearTimeout(timer);
            unsubscribe();
            unsubscribeReconnect();
        };
    }, [key, debounceMs]);
}
