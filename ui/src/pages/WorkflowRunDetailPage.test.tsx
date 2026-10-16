import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Route, Routes } from "react-router-dom";
import { WorkflowRunDetailPage } from "./WorkflowRunDetailPage";
import * as api from "../config/api";
import { renderWithRouter } from "../test/renderWithRouter";

vi.mock("../config/api", () => ({
    getWorkflowRun: vi.fn().mockResolvedValue({
        runId: 7, subjectId: 3, subjectName: "EricWittmann/cb-test-project#12", title: "Run seven",
        status: "completed", definitionId: 1, definitionName: "Apitomy Renovate PR Auto-Merge",
        definitionVersion: 4, startedOn: "2026-10-01T00:00:00Z", labels: {}, inputs: {}, workflowContent: null, instance: null,
    }),
    fetchSubjectTasks: vi.fn().mockResolvedValue([]),
    cancelWorkflowRun: vi.fn(),
}));
vi.mock("../config/sse", () => ({ sseClient: { subscribe: vi.fn(() => () => {}) } }));
vi.mock("@apitomy/flow-ui", () => ({ WorkflowViewer: () => <div data-testid="workflow-viewer" /> }));
vi.mock("../components/TraceGraph", () => ({ TraceGraph: () => null }));
vi.mock("../components/LineagePanel", () => ({ LineagePanel: () => null }));
vi.mock("../components/WorkflowStepTimeline", () => ({ WorkflowStepTimeline: () => null }));

describe("WorkflowRunDetailPage", () => {
    it("lists the Diagram tab first and opens on it", async () => {
        renderWithRouter(
            <Routes><Route path="/runs/:runId" element={<WorkflowRunDetailPage />} /></Routes>,
            "/runs/7");
        const tabs = await screen.findAllByRole("tab");
        expect(tabs.map((t) => t.textContent)).toEqual(
            ["Diagram", "Overview", "Timeline", "Execution Trace", "Lineage"]);
        expect(tabs[0]).toHaveAttribute("aria-selected", "true");
    });

    it("shows the overview as a form with links to the subject and workflow by full name", async () => {
        const { container } = renderWithRouter(
            <Routes><Route path="/runs/:runId" element={<WorkflowRunDetailPage />} /></Routes>,
            "/runs/7");
        await userEvent.click(await screen.findByRole("tab", { name: "Overview" }));
        expect(container.querySelector("form.pf-v6-c-form")).not.toBeNull();
        const subject = screen.getByRole("link", { name: "EricWittmann/cb-test-project#12" });
        expect(subject).toHaveAttribute("href", "/subjects/3");
        const workflow = screen.getByRole("link", { name: "Apitomy Renovate PR Auto-Merge" });
        expect(workflow).toHaveAttribute("href", "/components/workflows/1");
        const form = container.querySelector("form") as HTMLElement;
        expect(within(form).getByText("Run seven")).toBeInTheDocument();
        expect(within(form).getByText(/version 4/)).toBeInTheDocument();
    });

    it("names an untitled run after its subject and workflow, and omits the title field", async () => {
        vi.mocked(api.getWorkflowRun).mockResolvedValueOnce({
            runId: 8, subjectId: 3, subjectName: "Subj", status: "running", definitionId: 1,
            definitionName: "Flow", definitionVersion: 2, startedOn: "2026-10-01T00:00:00Z",
            labels: {}, inputs: {}, workflowContent: null,
        } as never);
        renderWithRouter(
            <Routes><Route path="/runs/:runId" element={<WorkflowRunDetailPage />} /></Routes>,
            "/runs/8");
        expect(await screen.findByRole("heading", { name: /Subj — Flow v2/ })).toBeInTheDocument();
        await userEvent.click(screen.getByRole("tab", { name: "Overview" }));
        expect(screen.queryByText("Title")).toBeNull();
    });
});
