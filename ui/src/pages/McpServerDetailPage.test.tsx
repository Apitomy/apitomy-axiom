import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { render } from "@testing-library/react";
import { McpServerDetailPage } from "./McpServerDetailPage";
import * as api from "../config/api";

vi.mock("../config/api", async (orig) => ({
    ...(await orig<typeof import("../config/api")>()),
    fetchMcpServer: vi.fn(),
    updateMcpServer: vi.fn(),
    testMcpServer: vi.fn(),
    fetchMcpServerUsage: vi.fn().mockResolvedValue([]),
    fetchSecrets: vi.fn().mockResolvedValue([]),
}));

function renderPage() {
    render(<MemoryRouter initialEntries={["/mcp-servers/5"]}><Routes>
        <Route path="/mcp-servers/:mcpServerId" element={<McpServerDetailPage />} />
    </Routes></MemoryRouter>);
}

describe("McpServerDetailPage", () => {
    it("shows the server's error message when saving fails", async () => {
        vi.mocked(api.fetchMcpServer).mockResolvedValue({ id: 5, name: "gh", serverCommand: "npx" } as never);
        vi.mocked(api.updateMcpServer).mockRejectedValueOnce(new Error("Invalid MCP server name 'a b'"));
        renderPage();
        const desc = await screen.findByLabelText("Description");
        await userEvent.type(desc, "x");
        await userEvent.click(screen.getByRole("button", { name: /Save Changes/ }));
        expect(await screen.findByText(/Invalid MCP server name/)).toBeInTheDocument();
    });

    it("preserves headers, timeout, enabled and sse transport when saving", async () => {
        vi.mocked(api.fetchMcpServer).mockResolvedValue({
            id: 5, name: "r", transport: "sse", serverUrl: "https://x.io/sse",
            headers: { Authorization: "Bearer t" }, timeout: 5000, enabled: false,
        } as never);
        vi.mocked(api.updateMcpServer).mockResolvedValueOnce({ id: 5, name: "r" } as never);
        renderPage();
        await userEvent.type(await screen.findByLabelText("Description"), "x");
        await userEvent.click(screen.getByRole("button", { name: /Save Changes/ }));
        expect(api.updateMcpServer).toHaveBeenLastCalledWith(5, expect.objectContaining({
            transport: "sse", headers: { Authorization: "Bearer t" }, timeout: 5000, enabled: false,
        }));
    });

    it("shows warnings returned by the server", async () => {
        vi.mocked(api.fetchMcpServer).mockResolvedValue({
            id: 5, name: "gh", transport: "stdio", serverCommand: "npx", enabled: true,
            warnings: ["Secret 'GH_TOKEN' does not exist"],
        } as never);
        renderPage();
        expect(await screen.findByText(/Secret 'GH_TOKEN' does not exist/)).toBeInTheDocument();
    });

    it("tests the connection and lists the tools", async () => {
        vi.mocked(api.fetchMcpServer).mockResolvedValue({
            id: 5, name: "gh", transport: "stdio", serverCommand: "npx", enabled: true,
        } as never);
        vi.mocked(api.testMcpServer).mockResolvedValueOnce({
            success: true, serverName: "github-mcp", serverVersion: "1.0", durationMs: 1200,
            tools: [{ name: "list_issues", description: "List issues" }, { name: "get_issue" }],
        });
        renderPage();
        await userEvent.click(await screen.findByRole("button", { name: /Test connection/ }));
        expect(await screen.findByText(/Connected to github-mcp 1.0/)).toBeInTheDocument();
        expect(screen.getAllByText("mcp__gh__list_issues").length).toBeGreaterThan(0);
        expect(screen.getAllByText("List issues").length).toBeGreaterThan(0);
        expect(screen.getByRole("tab", { name: "Tools (2)" })).toBeInTheDocument();
    });

    it("shows why a connection test failed, with the server log", async () => {
        vi.mocked(api.fetchMcpServer).mockResolvedValue({
            id: 5, name: "gh", transport: "stdio", serverCommand: "npx", enabled: true,
        } as never);
        vi.mocked(api.testMcpServer).mockResolvedValueOnce({
            success: false, error: "Server exited with code 1 (command: npx)", log: "npm ERR! 404", tools: [], durationMs: 10,
        });
        renderPage();
        await userEvent.click(await screen.findByRole("button", { name: /Test connection/ }));
        expect(await screen.findByText(/Server exited with code 1/)).toBeInTheDocument();
        expect(screen.getByText(/npm ERR! 404/)).toBeInTheDocument();
    });

    it("switching to HTTP saves only the HTTP fields", async () => {
        vi.mocked(api.fetchMcpServer).mockResolvedValue({
            id: 5, name: "gh", transport: "stdio", serverCommand: "npx", serverArgs: ["-y"],
            serverEnv: { A: "b" }, enabled: true,
        } as never);
        vi.mocked(api.updateMcpServer).mockResolvedValueOnce({ id: 5, name: "gh", transport: "http", enabled: true } as never);
        renderPage();
        await userEvent.click(await screen.findByRole("tab", { name: "Connection" }));
        await userEvent.click(screen.getByLabelText("HTTP (streamable)"));
        await userEvent.type(screen.getByLabelText(/^URL/), "https://x.io/mcp");
        await userEvent.click(screen.getByRole("button", { name: /Save Changes/ }));
        expect(api.updateMcpServer).toHaveBeenLastCalledWith(5, {
            name: "gh", description: undefined, transport: "http", timeout: undefined, enabled: true,
            serverUrl: "https://x.io/mcp", headers: {},
        });
    });

    it("disables the server", async () => {
        vi.mocked(api.fetchMcpServer).mockResolvedValue({
            id: 5, name: "gh", transport: "stdio", serverCommand: "npx", enabled: true,
        } as never);
        vi.mocked(api.updateMcpServer).mockResolvedValueOnce({ id: 5, name: "gh", transport: "stdio", enabled: false } as never);
        renderPage();
        await userEvent.click(await screen.findByLabelText("Enabled"));
        await userEvent.click(screen.getByRole("button", { name: /Save Changes/ }));
        expect(api.updateMcpServer).toHaveBeenLastCalledWith(5, expect.objectContaining({ enabled: false }));
        expect(await screen.findByText(/This server is disabled/)).toBeInTheDocument();
    });

    it("lists the items that use the server", async () => {
        vi.mocked(api.fetchMcpServer).mockResolvedValue({
            id: 5, name: "gh", transport: "stdio", serverCommand: "npx", enabled: true,
        } as never);
        vi.mocked(api.fetchMcpServerUsage).mockResolvedValueOnce([
            { type: "sessionTemplate", id: 2, templateId: "t-1", name: "Triage chat", field: "mcpServers" },
        ]);
        renderPage();
        await userEvent.click(await screen.findByRole("tab", { name: "Used by (1)" }));
        expect(screen.getByRole("link", { name: "Triage chat" })).toHaveAttribute("href", "/session-templates/t-1");
    });
});
