import { screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { EngineSettingsPage } from "./EngineSettingsPage";
import { renderWithRouter } from "../test/renderWithRouter";

vi.mock("../config/api", async (orig) => ({
    ...(await orig<typeof import("../config/api")>()),
    fetchSystemConfig: vi.fn().mockResolvedValue({
        defaultEngine: "claude-code",
        engines: [{ type: "claude-code", label: "Claude Code", available: true,
            checks: [{ name: "Claude Code CLI", status: "ok", message: "ok" }] }],
        checks: [
            { name: "Node.js", status: "ok", required: true, message: "Node.js v22.1.0 is available." },
            { name: "npx", status: "ok", required: false, message: "npx 10.9.2 is available." },
            { name: "uvx", status: "warning", required: false, message: "uvx not found. Install uv: https://docs.astral.sh/uv/" },
            { name: "Docker", status: "warning", required: false, message: "the Docker daemon is not reachable" },
            { name: "Claude Code CLI", status: "ok", message: "ok" },
        ],
    }),
}));

describe("EngineSettingsPage prerequisites", () => {
    it("lists required and optional prerequisites but not engine checks", async () => {
        renderWithRouter(<EngineSettingsPage />, "/engine");
        const card = (await screen.findByText("Prerequisites")).closest(".pf-v6-c-card") as HTMLElement;
        const node = within(card).getByTestId("prerequisite-Node.js");
        expect(within(node).getByText("Required")).toBeInTheDocument();
        const uvx = within(card).getByTestId("prerequisite-uvx");
        expect(within(uvx).getByText("Optional")).toBeInTheDocument();
        expect(within(uvx).getByText(/docs.astral.sh/)).toBeInTheDocument();
        expect(within(card).getByTestId("prerequisite-npx")).toBeInTheDocument();
        expect(within(card).getByTestId("prerequisite-Docker")).toBeInTheDocument();
        expect(within(card).queryByText("Claude Code CLI")).not.toBeInTheDocument();
    });
});
