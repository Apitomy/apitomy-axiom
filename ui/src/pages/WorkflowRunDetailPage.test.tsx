import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { Route, Routes } from "react-router-dom";
import { WorkflowRunDetailPage } from "./WorkflowRunDetailPage";
import { renderWithRouter } from "../test/renderWithRouter";

vi.mock("../config/api", () => ({
    getWorkflowRun: vi.fn().mockResolvedValue({
        runId: 7, subjectId: 3, title: "Run seven", status: "completed", definitionId: 1,
        definitionVersion: 1, labels: {}, inputs: {}, workflowContent: null, instance: null,
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
});
