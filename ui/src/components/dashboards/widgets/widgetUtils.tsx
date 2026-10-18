import { Spinner } from "@patternfly/react-core";
import type { ConfigField } from "../widget-registry";

/** Time windows offered by "since" config fields, in days. */
export const SINCE_WINDOWS: Record<string, number> = {
    "24h": 1, "7d": 7, "30d": 30, "90d": 90,
};

/**
 * Builds a "since" select config field (same choices as the AI cost widgets).
 *
 * @param defaultValue the default window key
 * @param label the field label
 * @returns the config field
 */
export function sinceField(defaultValue = "24h", label = "Time Window"): ConfigField {
    return {
        key: "since", label, type: "select", default: defaultValue,
        options: [
            { label: "24 Hours", value: "24h" },
            { label: "7 Days", value: "7d" },
            { label: "30 Days", value: "30d" },
            { label: "90 Days", value: "90d" },
        ],
    };
}

/**
 * Converts a window key to an ISO-8601 start time.
 *
 * @param window the window key (unknown keys fall back to 24h)
 * @param now the current time in ms
 * @returns the ISO-8601 date-time
 */
export function sinceIso(window: unknown, now: number = Date.now()): string {
    const days = SINCE_WINDOWS[String(window)] ?? 1;
    return new Date(now - days * 86400000).toISOString();
}

/**
 * Formats the time elapsed since a date as a compact age ("5m", "3h", "2d").
 *
 * @param iso the ISO-8601 date-time
 * @param now the current time in ms
 * @returns the age, or "-" when absent or invalid
 */
export function formatAge(iso: string | undefined, now: number = Date.now()): string {
    if (!iso) return "-";
    const ms = now - new Date(iso).getTime();
    if (Number.isNaN(ms)) return "-";
    const minutes = Math.max(0, Math.floor(ms / 60000));
    if (minutes < 60) return `${minutes}m`;
    const hours = Math.floor(minutes / 60);
    if (hours < 48) return `${hours}h`;
    return `${Math.floor(hours / 24)}d`;
}

/**
 * Formats a date-time for compact display.
 *
 * @param iso the ISO-8601 date-time
 * @returns the localized date-time, or "-"
 */
export function formatTime(iso: string | undefined): string {
    if (!iso) return "-";
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? "-" : d.toLocaleString();
}

/** Centered loading spinner for widgets. */
export function WidgetLoading({ label }: { label: string }) {
    return <div style={{ padding: "12px", textAlign: "center" }}><Spinner size="md" aria-label={label} /></div>;
}

/** Centered empty-state message for widgets. */
export function WidgetEmpty({ children }: { children: React.ReactNode }) {
    return <p style={{ padding: "16px", textAlign: "center" }}>{children}</p>;
}

/** Shared compact table cell style. */
export const cellStyle: React.CSSProperties = { padding: "4px 6px", fontSize: "0.85rem", verticalAlign: "top" };
