import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { WorkflowTab } from "./WorkflowTab";
import { renderWithRouter } from "../test/renderWithRouter";
import * as api from "../config/api";

vi.mock("@apitomy/flow-ui", () => ({ WorkflowViewer: () => null }));
vi.mock("../config/api", () => ({
    getWorkflowInstance: vi.fn().mockRejectedValue(new Error("none")),
    fetchWorkflowDefinitions: vi.fn().mockResolvedValue({
        items: [{ id: 3, name: "Triage", currentVersion: 2 }], totalCount: 1,
    }),
    triggerWorkflow: vi.fn().mockResolvedValue({ id: 10 }),
    cancelWorkflowRun: vi.fn(),
}));

vi.stubGlobal("EventSource", class {
    addEventListener(): void {}
    removeEventListener(): void {}
    close(): void {}
});

async function openDialog() {
    renderWithRouter(<WorkflowTab subjectId={7} hasWorkflowInstance={false} onRefresh={vi.fn()} />);
    await userEvent.click((await screen.findAllByRole("button", { name: "Run Workflow" }))[0]);
    return screen.findByRole("dialog");
}

describe("WorkflowTab Run Workflow dialog", () => {
    it("sends the title, labels and inputs", async () => {
        const dialog = await openDialog();
        await userEvent.type(within(dialog).getByLabelText("Title"), "Retry triage");
        await userEvent.type(within(dialog).getByLabelText("Labels"), "team:core");
        await userEvent.type(within(dialog).getByLabelText("Inputs"), '{{"attempts": 2}');
        await userEvent.click(within(dialog).getByRole("button", { name: "Run Workflow" }));
        await waitFor(() => expect(api.triggerWorkflow).toHaveBeenCalledWith(7, {
            workflowDefinitionId: 3, title: "Retry triage", labels: { team: "core" }, inputs: { attempts: 2 },
        }));
    });

    it("blocks running with invalid inputs", async () => {
        const dialog = await openDialog();
        // "[[" types a literal "[" in user-event's keyboard syntax.
        await userEvent.type(within(dialog).getByLabelText("Inputs"), "[[1]");
        expect(within(dialog).getByText("Enter a JSON object.")).toBeInTheDocument();
        expect((within(dialog).getByRole("button", { name: "Run Workflow" }) as HTMLButtonElement).disabled).toBe(true);
    });
});
