import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import "../components/dashboards/widgets";
import { getAllWidgets, getDefaultConfig } from "../components/dashboards/widget-registry";
import type { Dashboard } from "../config/api";
import { DashboardViewPage } from "./DashboardViewPage";

vi.mock("../config/sse", () => ({
    sseClient: { subscribe: vi.fn(() => () => {}), onReconnect: vi.fn(() => () => {}) },
}));

const emptyPage = { items: [], totalCount: 0, page: 1, limit: 20 };

/** Returns an empty but well-formed response body for a widget API path. */
function bodyFor(path: string, dashboard: Dashboard): unknown {
    if (/\/dashboards\/\d+$/.test(path)) return dashboard;
    if (path.endsWith("/workflow/runs/summary")) {
        return { counts: { running: 0, waiting: 0, completed: 0, failed: 0, cancelled: 0 }, recentFailed: [] };
    }
    if (path.endsWith("/usage/ai/summary")) return { groupBy: "workflow", rows: [], totalCostUsd: 0 };
    if (path.endsWith("/usage/ai")) {
        return { ...emptyPage, totalCostUsd: 0, totalInputTokens: 0, totalOutputTokens: 0 };
    }
    if (path.endsWith("/system/health")) return { status: "UP", checks: [] };
    if (/\/(waiting|schedules|health|summary|failures|histogram)$/.test(path)) return [];
    return emptyPage;
}

describe("DashboardViewPage with every widget type", () => {
    const widgets = getAllWidgets();
    const dashboard: Dashboard = {
        id: 1, name: "All", labels: ["team=a"], isDefault: false, createdOn: "", updatedOn: "",
        tabs: [{
            id: "t", name: "Main",
            widgets: widgets.map((w, i) => ({
                id: `w${i}`, type: w.type, config: getDefaultConfig(w.type),
                layout: { x: 0, y: i * 3, w: 6, h: 3 },
            })),
        }],
    };
    let errorSpy: { mock: { calls: unknown[][] }; mockRestore: () => void };

    beforeEach(() => {
        errorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
        vi.stubGlobal("ResizeObserver", class {
            observe(): void {}
            unobserve(): void {}
            disconnect(): void {}
        });
        vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
            const path = new URL(String(input), "http://localhost").pathname;
            return Promise.resolve(new Response(JSON.stringify(bodyFor(path, dashboard)),
                { status: 200, headers: { "Content-Type": "application/json" } }));
        }));
    });
    afterEach(() => {
        vi.unstubAllGlobals();
        errorSpy.mockRestore();
    });

    it("renders every registered widget without crashing", async () => {
        expect(widgets.length).toBeGreaterThanOrEqual(20);
        render(
            <MemoryRouter initialEntries={["/dashboards/1"]}>
                <Routes><Route path="/dashboards/:dashboardId" element={<DashboardViewPage />} /></Routes>
            </MemoryRouter>
        );
        for (const w of widgets) {
            expect(await screen.findByText(w.name)).toBeInTheDocument();
        }
        // Let every widget's data request settle.
        await waitFor(() => expect(screen.queryAllByRole("progressbar")).toHaveLength(0));
        const failed = screen.queryAllByText("Error loading data.")
            .map((el) => el.parentElement?.textContent ?? "");
        expect(failed).toEqual([]);
        const crashes = errorSpy.mock.calls.filter((c) => String(c[0]).includes("Widget rendering error"));
        expect(crashes).toEqual([]);
    }, 15_000); // Renders 20+ widgets; routinely 3-5s, so the 5s default is too tight under parallel load.
});
