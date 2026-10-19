import { describe, expect, it } from "vitest";
import "./widgets";
import { getWidget } from "./widget-registry";
import { DASHBOARD_TEMPLATES, buildDashboardFromTemplate } from "./templates";

describe("dashboard templates", () => {
    it("only uses registered widget types and valid config keys", () => {
        for (const template of DASHBOARD_TEMPLATES) {
            for (const w of template.widgets) {
                const entry = getWidget(w.type);
                expect(entry, `${template.id}: ${w.type}`).toBeDefined();
                const keys = (entry!.configSchema ?? []).map((f) => f.key);
                for (const [key, value] of Object.entries(w.config ?? {})) {
                    expect(keys, `${template.id}: ${w.type}.${key}`).toContain(key);
                    const field = entry!.configSchema!.find((f) => f.key === key)!;
                    if (field.type === "select") {
                        expect(field.options!.map((o) => o.value)).toContain(value);
                    }
                }
            }
        }
    });

    it("lays widgets out without overlap inside 12 columns", () => {
        for (const template of DASHBOARD_TEMPLATES) {
            const cells = new Set<string>();
            for (const { layout: l } of template.widgets) {
                expect(l.x + l.w).toBeLessThanOrEqual(12);
                for (let x = l.x; x < l.x + l.w; x++) {
                    for (let y = l.y; y < l.y + l.h; y++) {
                        const cell = `${x},${y}`;
                        expect(cells.has(cell), `${template.id} overlap at ${cell}`).toBe(false);
                        cells.add(cell);
                    }
                }
            }
        }
    });

    it("builds a dashboard with default config merged with overrides", () => {
        const ai = DASHBOARD_TEMPLATES.find((t) => t.id === "ai-usage")!;
        const d = buildDashboardFromTemplate(ai, "AI", ["team-a"], false);
        expect(d.labels).toEqual(["team-a"]);
        expect(d.tabs).toHaveLength(1);
        const breakdown = d.tabs[0].widgets[2];
        expect(breakdown.config).toMatchObject({ groupBy: "model", since: "7d", limit: 10 });
        expect(new Set(d.tabs[0].widgets.map((w) => w.id)).size).toBe(4);
    });
});
