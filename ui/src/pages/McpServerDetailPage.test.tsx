import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { render } from "@testing-library/react";
import { McpServerDetailPage } from "./McpServerDetailPage";
import * as api from "../config/api";

vi.mock("../config/api", () => ({
    fetchMcpServer: vi.fn(),
    updateMcpServer: vi.fn(),
    testMcpServer: vi.fn(),
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
        expect(screen.getByText("mcp__gh__list_issues")).toBeInTheDocument();
        expect(screen.getByText("List issues")).toBeInTheDocument();
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
});
