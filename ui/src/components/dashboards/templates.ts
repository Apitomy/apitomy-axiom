import type { DashboardWidgetLayout, NewDashboard } from "../../config/api";
import { getDefaultConfig } from "./widget-registry";

/** A widget placed by a dashboard template. */
export interface TemplateWidget {
    /** Registered widget type id. */
    type: string;
    /** Config overrides merged on top of the widget's default config. */
    config?: Record<string, unknown>;
    /** Grid position on a 12-column layout. */
    layout: DashboardWidgetLayout;
}

/** A predefined dashboard that users can create in one step. */
export interface DashboardTemplate {
    id: string;
    name: string;
    description: string;
    widgets: TemplateWidget[];
}

/** Built-in dashboard templates. */
export const DASHBOARD_TEMPLATES: DashboardTemplate[] = [
    {
        id: "operations",
        name: "Operations",
        description: "Workflow runs by status, failed and waiting runs, tasks awaiting input, "
            + "application health and upcoming schedules.",
        widgets: [
            { type: "workflow-runs-summary", layout: { x: 0, y: 0, w: 4, h: 2 } },
            { type: "system-status", layout: { x: 4, y: 0, w: 3, h: 2 } },
            { type: "inbox", layout: { x: 7, y: 0, w: 5, h: 3 } },
            { type: "failed-runs", layout: { x: 0, y: 2, w: 7, h: 3 } },
            { type: "waiting-runs", layout: { x: 0, y: 5, w: 6, h: 3 } },
            { type: "upcoming-schedules", layout: { x: 6, y: 5, w: 6, h: 3 } },
        ],
    },
    {
        id: "event-operations",
        name: "Event Operations",
        description: "Event source health, subscription processing, processing failures, event volume "
            + "and recent events.",
        widgets: [
            { type: "event-source-health", layout: { x: 0, y: 0, w: 6, h: 3 } },
            { type: "subscription-processing", layout: { x: 6, y: 0, w: 6, h: 3 } },
            { type: "event-volume", layout: { x: 0, y: 3, w: 6, h: 3 } },
            { type: "routing-failures", layout: { x: 6, y: 3, w: 6, h: 3 } },
            { type: "recent-events", layout: { x: 0, y: 6, w: 12, h: 3 } },
        ],
    },
    {
        id: "ai-usage",
        name: "AI Usage",
        description: "AI cost summary, AI usage by workflow and by model, and AI cost by subject.",
        widgets: [
            { type: "ai-cost-summary", layout: { x: 0, y: 0, w: 4, h: 2 } },
            {
                type: "ai-usage-breakdown", config: { groupBy: "workflow" },
                layout: { x: 4, y: 0, w: 8, h: 3 },
            },
            {
                type: "ai-usage-breakdown", config: { groupBy: "model" },
                layout: { x: 0, y: 3, w: 6, h: 3 },
            },
            { type: "ai-cost-by-subject", layout: { x: 6, y: 3, w: 6, h: 3 } },
        ],
    },
];

/**
 * Finds a template by id.
 *
 * @param id the template id
 * @returns the template, or undefined
 */
export function getTemplate(id: string): DashboardTemplate | undefined {
    return DASHBOARD_TEMPLATES.find((t) => t.id === id);
}

/**
 * Builds a new dashboard (single tab) from a template.
 *
 * @param template the template
 * @param name the dashboard name
 * @param labels the dashboard (subject) labels
 * @param isDefault whether the new dashboard becomes the default
 * @returns the dashboard to POST
 */
export function buildDashboardFromTemplate(template: DashboardTemplate, name: string, labels: string[],
                                           isDefault: boolean): NewDashboard {
    return {
        name,
        description: template.description,
        labels,
        isDefault,
        tabs: [{
            id: crypto.randomUUID(),
            name: "Default",
            widgets: template.widgets.map((w) => ({
                id: crypto.randomUUID(),
                type: w.type,
                config: { ...getDefaultConfig(w.type), ...(w.config ?? {}) },
                layout: { ...w.layout },
            })),
        }],
    };
}
