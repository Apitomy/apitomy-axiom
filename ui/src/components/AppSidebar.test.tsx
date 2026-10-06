import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AppSidebar } from "./AppSidebar";
import { renderWithRouter } from "../test/renderWithRouter";

vi.mock("../config/api", () => ({
    fetchInboxCount: vi.fn().mockResolvedValue({ count: 0 }),
}));
vi.mock("../config/sse", () => ({
    sseClient: { subscribe: vi.fn(() => () => {}) },
}));

describe("AppSidebar", () => {
    it("renders the Inbox navigation item", async () => {
        renderWithRouter(<AppSidebar />);
        expect(await screen.findByText("Inbox")).toBeInTheDocument();
    });

    it("has no Manager navigation item", async () => {
        renderWithRouter(<AppSidebar />, "/engine");
        await screen.findByText("Inbox");
        expect(screen.queryByText(/manager/i)).not.toBeInTheDocument();
    });

    it("has no Reports or Report Definitions navigation items", async () => {
        renderWithRouter(<AppSidebar />, "/action-types");
        await screen.findByText("Inbox");
        expect(screen.queryByText("Reports")).not.toBeInTheDocument();
        expect(screen.queryByText("Report Definitions")).not.toBeInTheDocument();
    });

    it("has no Scheduled Jobs or Job Runs navigation items", async () => {
        renderWithRouter(<AppSidebar />, "/action-types");
        await screen.findByText("Inbox");
        expect(screen.queryByText("Scheduled Jobs")).not.toBeInTheDocument();
        expect(screen.queryByText("Job Runs")).not.toBeInTheDocument();
    });

    it("shows Subjects and not Projects", async () => {
        renderWithRouter(<AppSidebar />, "/subjects");
        await screen.findByText("Inbox");
        expect(screen.getByText("Subjects")).toBeInTheDocument();
        expect(screen.queryByText(/projects/i)).not.toBeInTheDocument();
    });
});
