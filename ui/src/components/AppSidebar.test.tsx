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
});
