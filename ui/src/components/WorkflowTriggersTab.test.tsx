import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { WorkflowTriggersTab } from "./WorkflowTriggersTab";
import { renderWithRouter } from "../test/renderWithRouter";

vi.mock("../config/api", () => ({
    fetchScheduleTriggers: vi.fn().mockResolvedValue([{
        id: 1, definitionId: 3, cron: "0 2 * * *", subjectName: "Nightly triage",
        enabled: true, lastOutcome: "skipped",
    }]),
    createScheduleTrigger: vi.fn(),
    updateScheduleTrigger: vi.fn(),
    deleteScheduleTrigger: vi.fn(),
}));

describe("WorkflowTriggersTab", () => {
    it("lists triggers and opens the add modal", async () => {
        renderWithRouter(<WorkflowTriggersTab definitionId={3} />);
        expect(await screen.findByText("0 2 * * *")).toBeInTheDocument();
        expect(screen.getByText("Nightly triage")).toBeInTheDocument();
        expect(screen.getByText("skipped")).toBeInTheDocument();

        await userEvent.click(screen.getByRole("button", { name: "Add trigger" }));
        const dialog: HTMLElement = await screen.findByRole("dialog");
        expect(within(dialog).getByRole("textbox", { name: /^Cron/ })).toBeInTheDocument();
        expect(within(dialog).getByRole("textbox", { name: /^Subject name/ })).toBeInTheDocument();
    });
});
