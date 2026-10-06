import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { WorkflowRunsPage } from "./WorkflowRunsPage";
import { renderWithRouter } from "../test/renderWithRouter";

function run(runId: number, title: string) {
    return {
        runId, title, subjectId: 1, subjectName: "Subject", definitionId: 2,
        definitionName: "Triage", definitionVersion: 1, status: "completed",
        startedOn: "2026-10-01T00:00:00Z", completedOn: "2026-10-01T00:01:00Z",
    };
}

vi.mock("../config/api", () => ({
    fetchWorkflowRuns: vi.fn().mockResolvedValue({
        items: [run(1, "Run A"), run(2, "Run B")], totalCount: 2, page: 1, limit: 20,
    }),
    fetchWorkflowDefinitions: vi.fn().mockResolvedValue({ items: [], totalCount: 0, page: 1, limit: 100 }),
}));
vi.mock("../config/sse", () => ({
    sseClient: { subscribe: vi.fn(() => () => {}) },
}));

describe("WorkflowRunsPage", () => {
    it("renders run titles and filter controls", async () => {
        renderWithRouter(<WorkflowRunsPage />);
        expect(await screen.findByText("Run A")).toBeInTheDocument();
        expect(screen.getByText("Run B")).toBeInTheDocument();
        expect(screen.getByLabelText("Status")).toBeInTheDocument();
        expect(screen.getByLabelText("Definition")).toBeInTheDocument();
        expect(screen.getByLabelText("Subject")).toBeInTheDocument();
        expect(screen.getByLabelText("Label")).toBeInTheDocument();
    });
});
