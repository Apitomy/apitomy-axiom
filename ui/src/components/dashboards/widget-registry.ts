import type { ComponentType } from "react";

// ── Types ────────────────────────────────────────────────────────

export interface WidgetProps {
    config: Record<string, unknown>;
    labels: string[];
    onConfigChange: (config: Record<string, unknown>) => void;
}

export interface ConfigField {
    key: string;
    label: string;
    type: "select" | "number" | "multiselect" | "toggle";
    options?: { label: string; value: string }[];
    default: unknown;
    min?: number;
    max?: number;
}

export interface WidgetRegistryEntry {
    type: string;
    name: string;
    description: string;
    category: string;
    defaultSize: { w: number; h: number };
    minSize?: { w: number; h: number };
    configSchema?: ConfigField[];
    component: ComponentType<WidgetProps>;
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
