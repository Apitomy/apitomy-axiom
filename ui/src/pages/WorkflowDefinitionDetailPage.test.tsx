import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Route, Routes } from "react-router-dom";
import { WorkflowDefinitionDetailPage } from "./WorkflowDefinitionDetailPage";
import * as api from "../config/api";
import { renderWithRouter } from "../test/renderWithRouter";

vi.mock("../config/api", () => ({
    getWorkflowDefinition: vi.fn().mockResolvedValue({
        id: 5, name: "Triage", description: "Triages issues", content: null, currentVersion: 1,
        createdOn: "2026-10-01T00:00:00Z", updatedOn: "2026-10-01T00:00:00Z",
    }),
    listWorkflowDefinitionVersions: vi.fn().mockResolvedValue([]),
    updateWorkflowDefinition: vi.fn().mockImplementation(async (id: number, data: object) => ({
        id, name: "Triage", createdOn: "2026-10-01T00:00:00Z", updatedOn: "2026-10-01T00:00:00Z", ...data,
    })),
    updateWorkflowDefinitionContent: vi.fn(),
    publishWorkflowDefinition: vi.fn(),
    deleteWorkflowDefinition: vi.fn(),
    getWorkflowDefinitionVersion: vi.fn(),
    fetchWorkflowDefinitionRuns: vi.fn().mockResolvedValue({ items: [], totalCount: 0, page: 1, limit: 20 }),
}));
vi.mock("../config/sse", () => ({ sseClient: { subscribe: vi.fn(() => () => {}) } }));
vi.mock("../config/compositions", () => ({ createComposition: vi.fn() }));
vi.mock("@apitomy/flow-ui", () => ({ WorkflowEditor: () => null, WorkflowDiffViewer: () => null }));
vi.mock("../hooks/useFocusNodeFromQuery", () => ({ useFocusNodeFromQuery: vi.fn() }));
vi.mock("../hooks/useWorkflowEditorSpi", () => ({ useWorkflowEditorSpi: vi.fn(() => ({})), findActionTypeIdByName: vi.fn() }));
vi.mock("../components/WorkflowTriggersTab", () => ({ WorkflowTriggersTab: () => null }));

describe("WorkflowDefinitionDetailPage edit modal", () => {
    it("offers an unchecked run-summary opt-in and saves it when checked", async () => {
        renderWithRouter(
            <Routes><Route path="/components/workflows/:workflowDefinitionId" element={<WorkflowDefinitionDetailPage />} /></Routes>,
            "/components/workflows/5");
        await userEvent.click(await screen.findByRole("button", { name: /Edit Info/ }));
        const dialog: HTMLElement = await screen.findByRole("dialog");
        const checkbox: HTMLElement = within(dialog).getByRole("checkbox",
            { name: "Generate AI summaries for completed and failed runs" });
        expect(checkbox).not.toBeChecked();
        await userEvent.click(checkbox);
        await userEvent.click(within(dialog).getByRole("button", { name: "Save" }));
        await waitFor(() => expect(api.updateWorkflowDefinition).toHaveBeenCalledWith(
            5, expect.objectContaining({ summarizeRuns: true })));
    });
});
