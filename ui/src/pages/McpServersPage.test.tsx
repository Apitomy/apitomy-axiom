import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { McpServersPage } from "./McpServersPage";
import * as api from "../config/api";
import { renderWithRouter } from "../test/renderWithRouter";

vi.mock("../config/api", async (orig) => ({
    ...(await orig<typeof import("../config/api")>()),
    fetchMcpServers: vi.fn().mockResolvedValue([]),
    createMcpServer: vi.fn(),
    deleteMcpServer: vi.fn(),
    testNewMcpServer: vi.fn(),
    searchMcpCatalog: vi.fn().mockResolvedValue([{ id: "github", source: "curated", name: "github", title: "GitHub",
        transport: "http", url: "https://api.githubcopilot.com/mcp/", runtime: "remote" }]),
    fetchMcpRuntimeStatus: vi.fn().mockResolvedValue({}),
    fetchSecrets: vi.fn().mockResolvedValue([]),
}));

describe("McpServersPage create modal", () => {
    beforeEach(() => {
        vi.mocked(api.createMcpServer).mockReset();
    });

    it("creates a stdio server with a command", async () => {
        vi.mocked(api.createMcpServer).mockResolvedValueOnce({ id: 1, name: "gh" } as never);
        renderWithRouter(<McpServersPage />, "/mcp-servers");
        await userEvent.click(await screen.findByRole("button", { name: "Add MCP Server" }));
        const dialog = await screen.findByRole("dialog");
        await userEvent.type(within(dialog).getByLabelText(/^Name/), "gh");
        const create = within(dialog).getByRole("button", { name: "Create" });
        expect(create).toBeDisabled();
        await userEvent.type(within(dialog).getByLabelText(/^Command/), "npx");
        await userEvent.click(create);
        expect(api.createMcpServer).toHaveBeenCalledWith({ name: "gh", transport: "stdio", serverCommand: "npx" });
    });

    it("creates an HTTP server with a URL", async () => {
        vi.mocked(api.createMcpServer).mockResolvedValueOnce({ id: 2, name: "r" } as never);
        renderWithRouter(<McpServersPage />, "/mcp-servers");
        await userEvent.click(await screen.findByRole("button", { name: "Add MCP Server" }));
        const dialog = await screen.findByRole("dialog");
        await userEvent.type(within(dialog).getByLabelText(/^Name/), "r");
        await userEvent.click(within(dialog).getByLabelText(/HTTP/));
        await userEvent.type(within(dialog).getByLabelText(/^URL/), "https://x.io/mcp");
        await userEvent.click(within(dialog).getByRole("button", { name: "Create" }));
        expect(api.createMcpServer).toHaveBeenCalledWith({ name: "r", transport: "http", serverUrl: "https://x.io/mcp" });
    });

    it("shows the server's error message when create fails", async () => {
        vi.mocked(api.createMcpServer).mockRejectedValueOnce(new Error("An MCP server named 'gh' already exists"));
        renderWithRouter(<McpServersPage />, "/mcp-servers");
        await userEvent.click(await screen.findByRole("button", { name: "Add MCP Server" }));
        const dialog = await screen.findByRole("dialog");
        await userEvent.type(within(dialog).getByLabelText(/^Name/), "gh");
        await userEvent.type(within(dialog).getByLabelText(/^Command/), "npx");
        await userEvent.click(within(dialog).getByRole("button", { name: "Create" }));
        expect(await within(dialog).findByText(/already exists/)).toBeInTheDocument();
    });

    it("explains why a delete was refused", async () => {
        vi.mocked(api.fetchMcpServers).mockResolvedValue([
            { id: 9, name: "gh", transport: "stdio", serverCommand: "npx", enabled: true },
        ] as never);
        vi.mocked(api.deleteMcpServer).mockRejectedValueOnce(Object.assign(
            new Error("MCP server 'gh' is in use by 1 item(s); remove those references before deleting it"),
            { usages: [{ type: "actionType", id: 4, name: "triage", field: "mcpServers" }] }));
        renderWithRouter(<McpServersPage />, "/mcp-servers");
        await userEvent.click(await screen.findByRole("button", { name: "Delete gh" }));
        const dialog = await screen.findByRole("dialog");
        await userEvent.click(within(dialog).getByRole("button", { name: /Delete/ }));
        expect(await screen.findByText(/is in use/)).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "triage" })).toHaveAttribute("href", "/action-types/4");
    });

    it("imports servers from a pasted mcpServers snippet", async () => {
        vi.mocked(api.fetchMcpServers).mockResolvedValue([]);
        vi.mocked(api.createMcpServer).mockResolvedValue({ id: 1, name: "x" } as never);
        renderWithRouter(<McpServersPage />, "/mcp-servers");
        await userEvent.click(await screen.findByRole("button", { name: "Add MCP Server" }));
        const dialog = await screen.findByRole("dialog");
        await userEvent.click(within(dialog).getByRole("tab", { name: "Paste JSON" }));
        const json = '{"mcpServers": {"fs": {"command": "npx", "args": ["-y", "fs"]}, '
            + '"remote": {"type": "http", "url": "https://x.io/mcp", "headers": {"Authorization": "Bearer abc"}}}}';
        await userEvent.click(within(dialog).getByLabelText("MCP server configuration"));
        await userEvent.paste(json);
        expect(within(dialog).getByText(/2 server\(s\): fs, remote/)).toBeInTheDocument();
        expect(within(dialog).getByText(/look like credentials/)).toBeInTheDocument();
        await userEvent.click(within(dialog).getByRole("button", { name: "Import 2 servers" }));
        expect(api.createMcpServer).toHaveBeenCalledWith(
            { name: "fs", transport: "stdio", serverCommand: "npx", serverArgs: ["-y", "fs"] });
        expect(api.createMcpServer).toHaveBeenCalledWith(
            { name: "remote", transport: "http", serverUrl: "https://x.io/mcp", headers: { Authorization: "Bearer abc" } });
    });

    it("tests a server before creating it", async () => {
        vi.mocked(api.testNewMcpServer).mockResolvedValueOnce({
            success: true, serverName: "fs", tools: [{ name: "read_file" }], durationMs: 300,
        });
        renderWithRouter(<McpServersPage />, "/mcp-servers");
        await userEvent.click(await screen.findByRole("button", { name: "Add MCP Server" }));
        const dialog = await screen.findByRole("dialog");
        await userEvent.type(within(dialog).getByLabelText(/^Name/), "fs");
        await userEvent.type(within(dialog).getByLabelText(/^Command/), "npx");
        await userEvent.click(within(dialog).getByRole("button", { name: "Test" }));
        expect(await within(dialog).findByText(/Connected to fs/)).toBeInTheDocument();
        expect(within(dialog).getByText("mcp__fs__read_file")).toBeInTheDocument();
    });

    it("filters the list and shows status and usage", async () => {
        vi.mocked(api.fetchMcpServers).mockResolvedValue([
            { id: 1, name: "github", transport: "stdio", enabled: true, usageCount: 3 },
            { id: 2, name: "jira", transport: "sse", enabled: false, usageCount: 0 },
        ] as never);
        renderWithRouter(<McpServersPage />, "/mcp-servers");
        expect(await screen.findByText("Disabled")).toBeInTheDocument();
        expect(screen.getByText("SSE")).toBeInTheDocument();
        await userEvent.type(screen.getByLabelText("Search MCP servers"), "jir");
        expect(screen.queryByText("github")).not.toBeInTheDocument();
        expect(screen.getByText("jira")).toBeInTheDocument();
    });

    it("opens the catalog", async () => {
        renderWithRouter(<McpServersPage />, "/mcp-servers");
        await userEvent.click(await screen.findByRole("button", { name: "Add from catalog" }));
        expect(await screen.findByRole("button", { name: "Select GitHub" })).toBeInTheDocument();
    });
});
