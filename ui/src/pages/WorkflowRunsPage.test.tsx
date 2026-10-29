import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { render } from "@testing-library/react";
import { normalizeRunFilter, resolveWorkflowFilter, WorkflowRunsPage } from "./WorkflowRunsPage";
import { renderWithRouter } from "../test/renderWithRouter";
import * as api from "../config/api";

function run(runId: number, title?: string) {
    return {
        runId, title, subjectId: 1, subjectName: `Subject ${runId}`, definitionId: 2,
        definitionName: "Triage", definitionVersion: 1, status: "completed",
        startedOn: "2026-10-01T00:00:00Z", completedOn: "2026-10-01T00:01:00Z",
    };
}

vi.mock("../config/api", () => ({
    fetchWorkflowRuns: vi.fn(),
    fetchWorkflowDefinitions: vi.fn(),
}));
vi.mock("../config/sse", () => ({
    sseClient: { subscribe: vi.fn(() => () => {}) },
}));

beforeEach(() => {
    vi.mocked(api.fetchWorkflowRuns).mockReset().mockResolvedValue({
        items: [run(1, "Run A"), run(2)], totalCount: 2, page: 1, limit: 20,
    } as never);
    vi.mocked(api.fetchWorkflowDefinitions).mockReset().mockResolvedValue({
        items: [{ id: 2, name: "Triage" }], totalCount: 1, page: 1, limit: 100,
    } as never);
});

function lastFilters(): Record<string, unknown> {
    const calls = vi.mocked(api.fetchWorkflowRuns).mock.calls;
    return calls[calls.length - 1][2] as Record<string, unknown>;
}

describe("WorkflowRunsPage", () => {
    it("renders Run (subject + optional title) and Workflow (name + version) columns", async () => {
        renderWithRouter(<WorkflowRunsPage />);
        expect(await screen.findByText("Subject 1")).toBeInTheDocument();
        expect(screen.getByText("Run A")).toBeInTheDocument();
        expect(screen.getByText("Subject 2")).toBeInTheDocument();
        expect(screen.getAllByText("Triage v1")).toHaveLength(2);
        const headers: string[] = screen.getAllByRole("columnheader").map((h) => h.textContent ?? "");
        expect(headers).toEqual(["Status", "Run", "Workflow", "Started"]);
        expect(screen.queryByRole("link")).toBeNull();
        expect(screen.queryByLabelText("Status")).toBeNull();
        expect(screen.queryByPlaceholderText("Subject ID")).toBeNull();
        expect(screen.queryByText("No workflow runs match the current filters.")).toBeNull();
    });

    it("opens the run detail page when a row is clicked", async () => {
        render(
            <MemoryRouter initialEntries={["/runs"]}>
                <Routes>
                    <Route path="/runs" element={<WorkflowRunsPage />} />
                    <Route path="/runs/:runId" element={<p>run detail page</p>} />
                </Routes>
            </MemoryRouter>);
        await userEvent.click(await screen.findByText("Subject 2"));
        expect(await screen.findByText("run detail page")).toBeInTheDocument();
    });

    it("adds a status chip from the table without leaving the page, and filters by it", async () => {
        renderWithRouter(<WorkflowRunsPage />);
        await screen.findByText("Run A");
        await userEvent.click(screen.getAllByText("completed")[0]);
        await waitFor(() => expect(lastFilters().status).toBe("completed"));
        expect(screen.getByText("Filters")).toBeInTheDocument();
        await userEvent.click(screen.getByRole("button", { name: "Close label group Filters" }));
        await waitFor(() => expect(lastFilters()).toEqual(expect.objectContaining({ status: undefined })));
    });

    it("normalises and validates typed filter values", () => {
        expect(normalizeRunFilter("status", " Running ")).toBe("running");
        expect(normalizeRunFilter("subject", "12")).toBe("12");
        expect(normalizeRunFilter("subject", "abc")).toBeNull();
        expect(normalizeRunFilter("label", "team:core")).toBe("team:core");
        expect(normalizeRunFilter("label", " triaged ")).toBe("triaged");
        expect(normalizeRunFilter("label", "  ")).toBeNull();
        expect(normalizeRunFilter("workflow", "  ")).toBeNull();
    });

    it("resolves a workflow filter by name or id, or to no match", () => {
        const defs = [{ id: 2, name: "Triage" }] as never;
        expect(resolveWorkflowFilter("triage", defs)).toBe(2);
        expect(resolveWorkflowFilter("7", defs)).toBe(7);
        expect(resolveWorkflowFilter("nope", defs)).toBe(-1);
    });
});
