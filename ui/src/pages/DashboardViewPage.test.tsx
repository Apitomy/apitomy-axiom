import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Link, MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Dashboard } from "../config/api";
import { fetchDashboard, updateDashboard } from "../config/api";
import { DashboardViewPage } from "./DashboardViewPage";

vi.mock("../config/api", async (importOriginal) => ({
    ...(await importOriginal<typeof import("../config/api")>()),
    fetchDashboard: vi.fn(),
    updateDashboard: vi.fn(),
    deleteDashboard: vi.fn(),
}));
vi.mock("../config/sse", () => ({
    sseClient: { subscribe: vi.fn(() => () => {}), onReconnect: vi.fn(() => () => {}) },
}));

function dashboard(id: number, tabNames: string[], extra: Partial<Dashboard> = {}): Dashboard {
    return {
        id, name: `Dash ${id}`, labels: [], isDefault: false, createdOn: "", updatedOn: "",
        tabs: tabNames.map((n) => ({ id: `${id}-${n}`, name: n, widgets: [] })),
        ...extra,
    };
}

function renderAt(path: string) {
    render(
        <MemoryRouter initialEntries={[path]}>
            <Routes>
                <Route path="/dashboards/:dashboardId" element={<>
                    <DashboardViewPage />
                    <Link to="/dashboards/2">go to 2</Link>
                </>} />
                <Route path="/dashboards/all" element={<div>LIST PAGE</div>} />
            </Routes>
        </MemoryRouter>
    );
}

describe("DashboardViewPage", () => {
    beforeEach(() => {
        vi.mocked(fetchDashboard).mockImplementation((id: number) =>
            Promise.resolve(dashboard(id, id === 1 ? ["Overview", "Ops"] : ["Main", "Second"])));
    });

    it("resets the active tab when switching dashboards", async () => {
        renderAt("/dashboards/1");
        await userEvent.click(await screen.findByRole("tab", { name: "Ops" }));
        expect(screen.getByRole("tab", { name: "Ops" })).toHaveAttribute("aria-selected", "true");

        await userEvent.click(screen.getByRole("link", { name: "go to 2" }));
        const main = await screen.findByRole("tab", { name: "Main" });
        await waitFor(() => expect(main).toHaveAttribute("aria-selected", "true"));
    });

    it("links to the dashboard list", async () => {
        renderAt("/dashboards/1");
        await userEvent.click(await screen.findByRole("link", { name: "All dashboards" }));
        expect(await screen.findByText("LIST PAGE")).toBeInTheDocument();
    });

    it("shows an error alert when setting the default fails", async () => {
        vi.mocked(updateDashboard).mockRejectedValue(new Error("Boom from server"));
        renderAt("/dashboards/1");
        await userEvent.click(await screen.findByRole("button", { name: "Set as Default" }));
        expect(await screen.findByText("Failed to set default dashboard")).toBeInTheDocument();
        expect(screen.getByText("Boom from server")).toBeInTheDocument();
    });

    it("shows an error alert when saving fails", async () => {
        vi.mocked(updateDashboard).mockRejectedValue(new Error("Dashboard name must not be blank"));
        renderAt("/dashboards/1");
        await userEvent.click(await screen.findByRole("button", { name: "Edit" }));
        await userEvent.click(screen.getByRole("button", { name: "Save" }));
        expect(await screen.findByText("Failed to save dashboard")).toBeInTheDocument();
        expect(screen.getByText("Dashboard name must not be blank")).toBeInTheDocument();
    });
});
