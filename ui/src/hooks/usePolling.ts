import { useEffect, useRef } from "react";

/**
 * Calls `fn` every `intervalMs` while the document is visible. Ticks that fire while the tab is hidden are
 * skipped, and `fn` is called once when the tab becomes visible again so the data is fresh on return.
 *
 * @param fn the callback to invoke on each poll (the latest version is always used)
 * @param intervalMs the polling interval in milliseconds
 */
export function usePolling(fn: () => void, intervalMs: number): void {
    const fnRef = useRef(fn);
    fnRef.current = fn;

    useEffect(() => {
        const timer = setInterval(() => {
            if (!document.hidden) fnRef.current();
        }, intervalMs);
        const onVisibility = () => {
            if (!document.hidden) fnRef.current();
        };
        document.addEventListener("visibilitychange", onVisibility);
        return () => {
            clearInterval(timer);
            document.removeEventListener("visibilitychange", onVisibility);
        };
    }, [intervalMs]);
}
