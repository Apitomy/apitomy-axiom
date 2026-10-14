import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

vi.mock("@apitomy/flow-ui", () => ({
    WorkflowViewer: (p: { instance: { id: string } }) => <div data-testid="viewer">viewer {p.instance.id}</div>,
}));
vi.mock("../../hooks/useTheme", () => ({ useEffectiveTheme: () => "light" }));
type Listener = (e: { type: string; data: Record<string, unknown> }) => void;
const listeners: Listener[] = [];
vi.mock("../../config/sse", () => ({
    sseClient: { subscribe: (l: Listener) => { listeners.push(l); return () => listeners.splice(listeners.indexOf(l), 1); } },
}));
const getWorkflowRun = vi.fn();
const fetchSubjectTasks = vi.fn();
const fetchTaskExecutionLog = vi.fn();
vi.mock("../../config/api", () => ({
    getWorkflowRun: (...a: unknown[]) => getWorkflowRun(...a),
    fetchSubjectTasks: (...a: unknown[]) => fetchSubjectTasks(...a),
    fetchTaskExecutionLog: (...a: unknown[]) => fetchTaskExecutionLog(...a),
}));

import { RunDetailDialog } from "./RunDetailDialog";

const failedRun = {
    id: 9, runId: 9, subjectId: 3, definitionId: 2, definitionVersion: 4, definitionName: "triage", title: "Triage it",
    status: "failed", currentNodeId: "n2", currentNodeName: "classify", failureReason: "Task failed",
    workflowContent: { nodes: [] }, context: { answer: 42 }, history: [],
    startedOn: "2026-10-14T10:00:00Z", completedOn: "2026-10-14T10:00:05Z",
};

function renderDialog(props: Partial<Parameters<typeof RunDetailDialog>[0]> = {}) {
    const onClose = vi.fn();
    const onAskAi = vi.fn();
    render(<MemoryRouter><RunDetailDialog runId={9} workflowName="triage" onClose={onClose} onAskAi={onAskAi} {...props} /></MemoryRouter>);
    return { onClose, onAskAi };
}

describe("RunDetailDialog", () => {
    beforeEach(() => {
        listeners.length = 0;
        getWorkflowRun.mockReset().mockResolvedValue(failedRun);
        fetchSubjectTasks.mockReset().mockResolvedValue([
            { id: 31, subjectId: 3, workflowRunId: 9, nodeId: "classify", actionType: "x", status: "failed",
                output: "{\"error\":\"bad\"}", createdOn: "c", createdBy: "u" },
            { id: 32, subjectId: 3, workflowRunId: 9, nodeId: "start", actionType: "x", status: "completed",
                output: "ok", createdOn: "c", createdBy: "u" },
            { id: 33, subjectId: 3, workflowRunId: 8, nodeId: "other", actionType: "x", status: "failed",
                output: "not mine", createdOn: "c", createdBy: "u" },
        ]);
        fetchTaskExecutionLog.mockReset().mockResolvedValue("log line 1");
    });

    it("renders title, metadata, failure alert and the graph", async () => {
        renderDialog();
        expect(await screen.findByText("Run #9: Triage it")).toBeTruthy();
        expect(getWorkflowRun).toHaveBeenCalledWith(9);
        expect(screen.getByText("failed")).toBeTruthy();
        expect(screen.getByText("v4")).toBeTruthy();
        expect(screen.getByText("5.0s")).toBeTruthy();
        expect(screen.getByText("classify")).toBeTruthy();
        expect(screen.getByText("Task failed")).toBeTruthy();
        expect(screen.getByTestId("viewer").textContent).toBe("viewer 9");
        expect(screen.getByRole("tab", { name: "Graph" })).toBeTruthy();
        expect(screen.getByRole("tab", { name: "Failure" })).toBeTruthy();
        expect(screen.getByRole("tab", { name: "Context" })).toBeTruthy();
        const link = screen.getByRole("link", { name: "Open run page" });
        expect(link.getAttribute("href")).toBe("/runs/9");
        expect(link.getAttribute("target")).toBe("_blank");
    });

    it("shows failed task output and log in the Failure tab", async () => {
        renderDialog();
        fireEvent.click(await screen.findByRole("tab", { name: "Failure" }));
        await waitFor(() => expect(fetchTaskExecutionLog).toHaveBeenCalledWith(3, 31));
        expect(await screen.findByText("log line 1")).toBeTruthy();
        expect(screen.getByLabelText("Output of classify").getAttribute("data-language")).toBe("json");
        expect(screen.queryByText("not mine")).toBeNull();
        expect(screen.queryByLabelText("Output of start")).toBeNull();
    });

    it("shows the context as JSON", async () => {
        renderDialog();
        fireEvent.click(await screen.findByRole("tab", { name: "Context" }));
        expect(screen.getByLabelText("Context", { selector: "div" }).getAttribute("data-language")).toBe("json");
    });

    it("asks the AI with the analysis prefill and closes", async () => {
        const { onAskAi, onClose } = renderDialog();
        fireEvent.click(await screen.findByRole("button", { name: "Ask AI to analyze" }));
        expect(onAskAi).toHaveBeenCalledWith("Analyze run #9 of workflow `triage` (status: failed at node `classify`): "
            + "Task failed. Find the cause and fix the workflow or its components.");
        expect(onClose).toHaveBeenCalled();
    });

    it("has no Failure tab or Ask AI for completed runs", async () => {
        getWorkflowRun.mockResolvedValue({ ...failedRun, status: "completed", failureReason: undefined });
        renderDialog();
        await screen.findByText("Run #9: Triage it");
        expect(screen.queryByRole("tab", { name: "Failure" })).toBeNull();
        expect(screen.queryByRole("button", { name: "Ask AI to analyze" })).toBeNull();
    });

    it("refetches on workflow-updated for its run", async () => {
        vi.useFakeTimers({ shouldAdvanceTime: true });
        try {
            renderDialog();
            await screen.findByText("Run #9: Triage it");
            const calls: number = getWorkflowRun.mock.calls.length;
            listeners.forEach((l) => l({ type: "workflow-updated", data: { runId: 8 } }));
            listeners.forEach((l) => l({ type: "workflow-updated", data: { runId: 9 } }));
            await vi.advanceTimersByTimeAsync(400);
            expect(getWorkflowRun.mock.calls.length).toBe(calls + 1);
        } finally {
            vi.useRealTimers();
        }
    });
});
