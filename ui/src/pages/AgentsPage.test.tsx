import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { render } from "@testing-library/react";
import { AgentsPage } from "./AgentsPage";
import * as api from "../config/api";
import { renderWithRouter } from "../test/renderWithRouter";

vi.mock("../config/api", () => ({
    fetchAgents: vi.fn().mockResolvedValue([]),
    createAgent: vi.fn(),
    updateAgent: vi.fn(),
    deleteAgent: vi.fn(),
}));

describe("AgentsPage", () => {
    it("explains that agents form a pool that limits concurrent AI jobs", async () => {
        renderWithRouter(<AgentsPage />, "/agents");
        const intro = await screen.findByText(/Agents form a pool of workers/i);
        expect(intro).toHaveTextContent(/one task at a time/i);
        expect(intro).toHaveTextContent(/how many AI tasks can run concurrently/i);
    });

    it("opens a help modal describing concurrency, capabilities and engines", async () => {
        renderWithRouter(<AgentsPage />, "/agents");
        await userEvent.click(await screen.findByRole("button", { name: "How the agent pool works" }));
        const dialog = await screen.findByRole("dialog");
        expect(within(dialog).getByText(/run at the same time/i)).toBeInTheDocument();
        expect(within(dialog).getAllByText(/capabilit/i).length).toBeGreaterThan(0);
        expect(within(dialog).getAllByText(/engine/i).length).toBeGreaterThan(0);
        const closeButtons = within(dialog).getAllByRole("button", { name: "Close" });
        await userEvent.click(closeButtons[closeButtons.length - 1]);
        await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    });

    it("asks to confirm a delete without opening the agent", async () => {
        vi.mocked(api.fetchAgents).mockResolvedValueOnce([
            { id: 7, name: "Blinky", agentType: "claude-code", capabilities: [] } as never,
        ]);
        render(<MemoryRouter initialEntries={["/agents"]}><Routes>
            <Route path="/agents" element={<AgentsPage />} />
            <Route path="/agents/:id" element={<div>agent opened</div>} />
        </Routes></MemoryRouter>);
        await userEvent.click(await screen.findByRole("button", { name: "Delete Blinky" }));
        expect(screen.queryByText("agent opened")).not.toBeInTheDocument();
        expect(await screen.findByRole("dialog")).toBeInTheDocument();
    });
});
