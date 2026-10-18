import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import type { Dashboard } from "../config/api";
import { deleteDashboard, fetchDashboards } from "../config/api";
import { DashboardHomePage, DashboardsPage } from "./DashboardsPage";

vi.mock("../config/api", async (importOriginal) => ({
    ...(await importOriginal<typeof import("../config/api")>()),
    fetchDashboards: vi.fn(),
    deleteDashboard: vi.fn(),
}));

function dash(id: number, isDefault: boolean): Dashboard {
    return { id, name: `Dash ${id}`, labels: [], isDefault, createdOn: "", updatedOn: "2026-01-01T00:00:00Z",
             tabs: [] };
}

function renderApp(path: string) {
    render(
        <MemoryRouter initialEntries={[path]}>
            <Routes>
                <Route path="/dashboards" element={<DashboardHomePage />} />
                <Route path="/dashboards/all" element={<DashboardsPage />} />
                <Route path="/dashboards/:dashboardId" element={<div>VIEW PAGE</div>} />
            </Routes>
        </MemoryRouter>
    );
}

describe("dashboard routing", () => {
    it("opens the default dashboard at /dashboards", async () => {
        vi.mocked(fetchDashboards).mockResolvedValue([dash(1, false), dash(2, true)]);
        renderApp("/dashboards");
        expect(await screen.findByText("VIEW PAGE")).toBeInTheDocument();
    });

    it("shows the list at /dashboards when there is no default", async () => {
        vi.mocked(fetchDashboards).mockResolvedValue([dash(1, false)]);
        renderApp("/dashboards");
        expect(await screen.findByRole("heading", { name: "Dashboards" })).toBeInTheDocument();
        expect(await screen.findByText("Dash 1")).toBeInTheDocument();
    });

    it("always shows the list at /dashboards/all", async () => {
        vi.mocked(fetchDashboards).mockResolvedValue([dash(2, true)]);
        renderApp("/dashboards/all");
        expect(await screen.findByText("Dash 2")).toBeInTheDocument();
        expect(screen.queryByText("VIEW PAGE")).not.toBeInTheDocument();
    });

    it("shows an error alert when deleting fails", async () => {
        vi.mocked(fetchDashboards).mockResolvedValue([dash(1, false)]);
        vi.mocked(deleteDashboard).mockRejectedValue(new Error("nope"));
        renderApp("/dashboards/all");
        await screen.findByText("Dash 1");
        await userEvent.click(screen.getByRole("button", { name: "Delete dashboard Dash 1" }));
        await userEvent.click(await screen.findByRole("button", { name: "Delete" }));
        expect(await screen.findByText("Failed to delete dashboard")).toBeInTheDocument();
    });
});
