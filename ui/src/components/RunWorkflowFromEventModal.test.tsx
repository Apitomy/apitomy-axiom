import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { parseInputs, parseLabels, RunWorkflowFromEventModal } from "./RunWorkflowFromEventModal";
import { renderWithRouter } from "../test/renderWithRouter";
import * as api from "../config/api";

vi.mock("../config/api", () => ({
    fetchWorkflowDefinitions: vi.fn(),
    fetchStreamEventWorkflowSuggestions: vi.fn(),
    triggerStreamEventWorkflow: vi.fn(),
}));

const EVENT_ID = "6b3f0d2e-1c1a-4c55-9f00-000000000001";

beforeEach(() => {
    vi.mocked(api.fetchWorkflowDefinitions).mockReset().mockResolvedValue({
        items: [
            { id: 1, name: "Zeta", currentVersion: 1 },
            { id: 2, name: "Alpha", currentVersion: 2 },
            { id: 3, name: "Triage", currentVersion: 1 },
            { id: 4, name: "Draft only" },
        ], totalCount: 4, page: 1, limit: 1000,
    } as never);
    vi.mocked(api.fetchStreamEventWorkflowSuggestions).mockReset().mockResolvedValue([
        { workflowDefinitionId: 3, workflowName: "Triage", subscriptionId: 9, subscriptionName: "GitHub issues" },
    ]);
    vi.mocked(api.triggerStreamEventWorkflow).mockReset();
});

function renderModal(onStarted = vi.fn()) {
    renderWithRouter(<RunWorkflowFromEventModal eventId={EVENT_ID} isOpen onClose={vi.fn()} onStarted={onStarted} />);
    return onStarted;
}

describe("RunWorkflowFromEventModal", () => {
    it("lists suggested workflows first, then published workflows alphabetically", async () => {
        renderModal();
        const select = await screen.findByLabelText(/Workflow/, { selector: "select" });
        await waitFor(() => expect(within(select).getAllByRole("option").length).toBe(5));
        const labels = within(select).getAllByRole("option").map((o) => o.textContent);
        expect(labels).toEqual([
            "Select a workflow",
            "Triage — used by subscription GitHub issues",
            "Alpha", "Triage", "Zeta",
        ]);
        expect(screen.getByText(/with the event in context.event/)).toBeInTheDocument();
        expect(document.activeElement).toBe(select);
    });

    it("sends the workflow, title, labels and inputs on Run", async () => {
        vi.mocked(api.triggerStreamEventWorkflow).mockResolvedValue({ id: 50, runId: 50 } as never);
        const onStarted = renderModal();
        const select = await screen.findByLabelText(/Workflow/, { selector: "select" });
        await waitFor(() => expect(within(select).getAllByRole("option").length).toBe(5));
        await userEvent.selectOptions(select, "2");
        await userEvent.type(screen.getByLabelText("Title"), "Manual triage");
        await userEvent.type(screen.getByLabelText("Labels"), "team:core, prio:high");
        await userEvent.type(screen.getByLabelText("Inputs"), '{{"x": 1}');
        await userEvent.click(screen.getByRole("button", { name: "Run" }));
        await waitFor(() => expect(onStarted).toHaveBeenCalled());
        expect(api.triggerStreamEventWorkflow).toHaveBeenCalledWith(EVENT_ID, {
            workflowDefinitionId: 2, title: "Manual triage",
            labels: { team: "core", prio: "high" }, inputs: { x: 1 },
        });
    });

    it("sends only the workflow id when optional fields are blank", async () => {
        vi.mocked(api.triggerStreamEventWorkflow).mockResolvedValue({ id: 50 } as never);
        renderModal();
        const select = await screen.findByLabelText(/Workflow/, { selector: "select" });
        await waitFor(() => expect(within(select).getAllByRole("option").length).toBe(5));
        await userEvent.selectOptions(select, "3");
        await userEvent.click(screen.getByRole("button", { name: "Run" }));
        await waitFor(() => expect(api.triggerStreamEventWorkflow).toHaveBeenCalledWith(
            EVENT_ID, { workflowDefinitionId: 3 }));
    });

    it("blocks Run while the inputs are not a JSON object", async () => {
        renderModal();
        const select = await screen.findByLabelText(/Workflow/, { selector: "select" });
        await waitFor(() => expect(within(select).getAllByRole("option").length).toBe(5));
        await userEvent.selectOptions(select, "2");
        await userEvent.type(screen.getByLabelText("Inputs"), "not json");
        expect(screen.getByText("Inputs must be a JSON object.")).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Run" })).toBeDisabled();
    });

    it("shows the server's conflict message inline", async () => {
        vi.mocked(api.triggerStreamEventWorkflow).mockRejectedValue(
            new Error("A workflow run is already active for this subject"));
        const onStarted = renderModal();
        const select = await screen.findByLabelText(/Workflow/, { selector: "select" });
        await waitFor(() => expect(within(select).getAllByRole("option").length).toBe(5));
        await userEvent.selectOptions(select, "3");
        await userEvent.click(screen.getByRole("button", { name: "Run" }));
        expect(await screen.findByText("A workflow run is already active for this subject")).toBeInTheDocument();
        expect(onStarted).not.toHaveBeenCalled();
    });

    it("parses labels and inputs", () => {
        expect(parseLabels("")).toEqual({});
        expect(parseLabels("a:b, c:d:e")).toEqual({ a: "b", c: "d:e" });
        expect(parseLabels("bad")).toBeNull();
        expect(parseInputs("")).toEqual({});
        expect(parseInputs("[1]")).toBeNull();
        expect(parseInputs('{"a":1}')).toEqual({ a: 1 });
    });
});
