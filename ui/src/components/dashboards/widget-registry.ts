import type { ComponentType } from "react";

// ── Types ────────────────────────────────────────────────────────

/**
 * Props passed to every widget component.
 */
export interface WidgetProps {
    /** The widget's saved configuration (keys from its configSchema). */
    config: Record<string, unknown>;
    /** Dashboard labels. SUBJECT labels: apply them only when the entry's scope is "subject-labels". */
    labels: string[];
    /** Persists a new configuration (only effective while the dashboard is being edited). */
    onConfigChange: (config: Record<string, unknown>) => void;
    /**
     * Increments whenever the widget should reload: after one of the entry's `liveEvents` arrives
     * (debounced) or the SSE connection reconnects. Add it to the dependencies of the data-loading
     * effect to opt in to live refresh. The dashboard's manual Refresh button remounts the widget.
     */
    refreshToken: number;
}

/** Widget categories shown in the Add Widget gallery, in display order. */
export const WIDGET_CATEGORIES = ["Runs", "Inbox", "Events", "AI & Cost", "System", "Subjects"] as const;

/** A widget category. */
export type WidgetCategory = typeof WIDGET_CATEGORIES[number];

/**
 * Whether a widget applies the dashboard labels (as subject labels) or ignores them.
 */
export type WidgetScope = "subject-labels" | "global";

/**
 * Config field types and the shape of their saved values:
 * - `select`: string (one of `options[].value`)
 * - `multiselect`: string[] (subset of `options[].value`; empty = all). Use {@link toStringArray} to read.
 * - `number`: number
 * - `toggle`: boolean
 * - `subject`: number | null (subject id)
 * - `workflow`: number | null (workflow definition id)
 * - `eventSource`: string | null (event source id)
 * - `subscription`: number | null (subscription id)
 */
export type ConfigFieldType =
    | "select" | "number" | "multiselect" | "toggle"
    | "subject" | "workflow" | "eventSource" | "subscription";

/** Entity selector field types. */
export const ENTITY_FIELD_TYPES: readonly ConfigFieldType[] = ["subject", "workflow", "eventSource", "subscription"];

export interface ConfigField {
    key: string;
    label: string;
    type: ConfigFieldType;
    /** Options for `select` and `multiselect`. */
    options?: { label: string; value: string }[];
    default: unknown;
    /** Bounds for `number`. */
    min?: number;
    max?: number;
    /** Optional helper text shown under the field. */
    helperText?: string;
}

export interface WidgetRegistryEntry {
    /** Stable type id stored in saved dashboards. Never rename. */
    type: string;
    name: string;
    description: string;
    category: WidgetCategory;
    /** How the widget treats dashboard labels. */
    scope: WidgetScope;
    /** SSE event names that trigger a (debounced) reload via `refreshToken`. */
    liveEvents?: string[];
    defaultSize: { w: number; h: number };
    minSize?: { w: number; h: number };
    configSchema?: ConfigField[];
    component: ComponentType<WidgetProps>;
}

/**
 * Normalises a saved multiselect value to a string array. Accepts legacy single-string values
 * ("" means none/all).
 *
 * @param value the saved config value
 * @returns the selected values
 */
export function toStringArray(value: unknown): string[] {
    if (Array.isArray(value)) return value.map(String);
    if (typeof value === "string" && value !== "") return [value];
    return [];
}

// ── Registry ─────────────────────────────────────────────────────

const registry = new Map<string, WidgetRegistryEntry>();

export function registerWidget(entry: WidgetRegistryEntry): void {
    registry.set(entry.type, entry);
}

export function getWidget(type: string): WidgetRegistryEntry | undefined {
    return registry.get(type);
}

export function getAllWidgets(): WidgetRegistryEntry[] {
    return Array.from(registry.values());
}

export function getWidgetsByCategory(): Map<string, WidgetRegistryEntry[]> {
    const byCategory = new Map<string, WidgetRegistryEntry[]>();
    for (const entry of registry.values()) {
        const list = byCategory.get(entry.category) ?? [];
        list.push(entry);
        byCategory.set(entry.category, list);
    }
    return byCategory;
}

export function getDefaultConfig(type: string): Record<string, unknown> {
    const entry = registry.get(type);
    if (!entry?.configSchema) return {};
    const config: Record<string, unknown> = {};
    for (const field of entry.configSchema) {
        config[field.key] = field.default;
    }
    return config;
}
