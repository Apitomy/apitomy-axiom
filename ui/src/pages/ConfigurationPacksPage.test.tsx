import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ConfigurationPacksPage } from "./ConfigurationPacksPage";
import { renderWithRouter } from "../test/renderWithRouter";
import * as api from "../config/api";

vi.mock("../config/api", () => ({
    fetchActionTypes: vi.fn().mockResolvedValue({ items: [], totalCount: 0, page: 1, limit: 100 }),
    fetchTools: vi.fn().mockResolvedValue({ items: [], totalCount: 0, page: 1, limit: 100 }),
    fetchToolsets: vi.fn().mockResolvedValue([]),
    fetchMcpServers: vi.fn().mockResolvedValue([]),
    fetchAssistantTemplates: vi.fn().mockResolvedValue([]),
    fetchEventSources: vi.fn().mockResolvedValue({ items: [], totalCount: 0, page: 1, limit: 100 }),
    fetchSubscriptions: vi.fn().mockResolvedValue({ items: [], totalCount: 0, page: 1, limit: 100 }),
    fetchWorkflowDefinitions: vi.fn().mockResolvedValue({ items: [], totalCount: 0, page: 1, limit: 100 }),
    fetchDashboards: vi.fn().mockResolvedValue([]),
    exportPack: vi.fn(),
    importPack: vi.fn().mockRejectedValue({
        status: 400,
        message: "Configuration pack has validation errors",
        problems: {
            "event-source/gh": [{
                severity: "error",
                path: "configuration.repositories[0]",
                code: "INVALID_CONFIGURATION",
                message: "Entry must be an 'owner/repo' string, got: \"bad\".",
            }],
        },
    }),
}));

describe("ConfigurationPacksPage", () => {
    it("shows per-item validation problems when import fails with 400", async () => {
        const user = userEvent.setup();
        const { container } = renderWithRouter(<ConfigurationPacksPage />, "/packs");
        await user.click(await screen.findByText("Import Pack"));

        const input = container.querySelector("input[type=file]") as HTMLInputElement;
        const file = new File([JSON.stringify({ eventSources: [{ id: "gh" }] })], "pack.json",
            { type: "application/json" });
        await user.upload(input, file);

        const importButton = await screen.findByRole("button", { name: "Import" });
        await vi.waitFor(() => expect(importButton).toBeEnabled());
        await user.click(importButton);

        expect(await screen.findByText(/Configuration pack has validation errors/)).toBeInTheDocument();
        expect(screen.getByText("event-source/gh")).toBeInTheDocument();
        expect(screen.getByText(/configuration\.repositories\[0\]/)).toBeInTheDocument();
        expect(screen.getByText(/Entry must be an 'owner\/repo' string/)).toBeInTheDocument();
    });

    it("imports with the chosen conflict mode", async () => {
        vi.mocked(api.importPack).mockResolvedValueOnce({ mcpServers: 2 });
        const user = userEvent.setup();
        const { container } = renderWithRouter(<ConfigurationPacksPage />, "/packs");
        await user.click(await screen.findByText("Import Pack"));
        const input = container.querySelector("input[type=file]") as HTMLInputElement;
        await user.upload(input, new File([JSON.stringify({ mcpServers: [{ name: "gh" }] })], "pack.json",
            { type: "application/json" }));
        await user.click(screen.getByRole("radio", { name: /Overwrite existing items/ }));
        const importButton = await screen.findByRole("button", { name: "Import" });
        await vi.waitFor(() => expect(importButton).toBeEnabled());
        await user.click(importButton);
        expect(api.importPack).toHaveBeenLastCalledWith(expect.any(String), "overwrite");
        expect(await screen.findByText(/2 MCP server/)).toBeInTheDocument();
    });

    it("warns before exporting MCP servers with plain credential values", async () => {
        vi.mocked(api.fetchMcpServers).mockResolvedValueOnce([
            { id: 7, name: "brave", transport: "stdio", enabled: true, serverEnv: { BRAVE_API_KEY: "plain" } },
            { id: 8, name: "safe", transport: "stdio", enabled: true, serverEnv: { TOKEN: "${secret:T}" } },
        ] as never);
        const user = userEvent.setup();
        renderWithRouter(<ConfigurationPacksPage />, "/packs");
        await user.click(await screen.findByRole("checkbox", { name: /safe/ }));
        expect(screen.queryByText(/plain-text credentials/)).not.toBeInTheDocument();
        await user.click(screen.getByRole("checkbox", { name: /brave/ }));
        expect(await screen.findByText(/plain-text credentials/)).toBeInTheDocument();
        expect(screen.getByText(/brave: BRAVE_API_KEY/)).toBeInTheDocument();
    });
});
