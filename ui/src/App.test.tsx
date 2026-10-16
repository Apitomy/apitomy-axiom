import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { renderWithRouter } from "./test/renderWithRouter";

vi.mock("./config/api", async (importOriginal) => ({
    ...(await importOriginal<typeof import("./config/api")>()),
    fetchSystemHealth: vi.fn().mockResolvedValue({}),
    fetchSystemConfig: vi.fn().mockResolvedValue({ engines: [] }),
    fetchInboxCount: vi.fn().mockResolvedValue({ count: 0 }),
    fetchWorkflowDefinitions: vi.fn().mockResolvedValue({ items: [], totalCount: 0, page: 1, limit: 100 }),
    fetchWorkflowRuns: vi.fn().mockResolvedValue({ items: [], totalCount: 0, page: 1, limit: 20 }),
}));
vi.mock("./config/sse", () => ({
    sseClient: { connect: vi.fn(), disconnect: vi.fn(), subscribe: vi.fn(() => () => {}) },
}));

window.matchMedia = vi.fn().mockReturnValue({
    matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn(),
}) as unknown as typeof window.matchMedia;

describe("App", () => {
    it("shows the Runs page at the root route", async () => {
        renderWithRouter(<App />, "/");
        expect(await screen.findByRole("heading", { name: "Runs" })).toBeInTheDocument();
    });
});
