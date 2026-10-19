import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ConfigurationPacksPage } from "./ConfigurationPacksPage";
import { renderWithRouter } from "../test/renderWithRouter";

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
});
