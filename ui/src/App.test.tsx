import { screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { fetchSystemConfig } from "./config/api";
import { renderWithRouter } from "./test/renderWithRouter";

vi.mock("./config/api", async (importOriginal) => ({
    ...(await importOriginal<typeof import("./config/api")>()),
    fetchSystemHealth: vi.fn().mockResolvedValue({}),
    fetchSystemConfig: vi.fn(),
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

const engine = (type: string, available: boolean) => ({
    type, label: type, available, checks: [{ name: type, status: available ? "ok" : "error", message: "m" }],
});

function mockConfig(config: object) {
    vi.mocked(fetchSystemConfig).mockResolvedValue(config as Awaited<ReturnType<typeof fetchSystemConfig>>);
}

describe("App", () => {
    beforeEach(() => {
        mockConfig({ engines: [engine("claude-code", true)], checks: [] });
    });

    it("shows the Runs page at the root route", async () => {
        renderWithRouter(<App />, "/");
        expect(await screen.findByRole("heading", { name: "Runs" })).toBeInTheDocument();
    });

    it("opens when one engine is available even though another engine reports errors", async () => {
        mockConfig({
            engines: [engine("claude-code", true), engine("copilot", false)],
            checks: [
                { name: "Node.js", status: "ok" },
                { name: "copilot", status: "error", message: "m" },
            ],
        });
        renderWithRouter(<App />, "/");
        expect(await screen.findByRole("heading", { name: "Runs" })).toBeInTheDocument();
        expect(screen.queryByText("Application Configuration Required")).not.toBeInTheDocument();
    });

    it("shows the configuration page when no engine is available", async () => {
        mockConfig({
            engines: [engine("claude-code", false), engine("copilot", false)],
            checks: [{ name: "claude-code", status: "error", message: "no CLI" }],
        });
        renderWithRouter(<App />, "/");
        expect(await screen.findByText("Application Configuration Required")).toBeInTheDocument();
        expect(screen.queryByRole("heading", { name: "Runs" })).not.toBeInTheDocument();
    });

    it("shows the configuration page when Node.js fails even though an engine is available", async () => {
        mockConfig({
            engines: [engine("claude-code", true)],
            checks: [{ name: "Node.js", status: "error", message: "not installed" }],
        });
        renderWithRouter(<App />, "/");
        expect(await screen.findByText("Application Configuration Required")).toBeInTheDocument();
        expect(screen.queryByRole("heading", { name: "Runs" })).not.toBeInTheDocument();
    });
});
