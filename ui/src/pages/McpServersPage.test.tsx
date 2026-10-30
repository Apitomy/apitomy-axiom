import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { McpServersPage } from "./McpServersPage";
import * as api from "../config/api";
import { renderWithRouter } from "../test/renderWithRouter";

vi.mock("../config/api", () => ({
    fetchMcpServers: vi.fn().mockResolvedValue([]),
    createMcpServer: vi.fn(),
    deleteMcpServer: vi.fn(),
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
        expect(api.createMcpServer).toHaveBeenCalledWith({ name: "gh", serverCommand: "npx" });
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
        expect(api.createMcpServer).toHaveBeenCalledWith({ name: "r", serverUrl: "https://x.io/mcp" });
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
});
