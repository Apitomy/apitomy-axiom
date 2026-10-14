import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { normalizeRunFilter, resolveWorkflowFilter, WorkflowRunsPage } from "./WorkflowRunsPage";
import { renderWithRouter } from "../test/renderWithRouter";
import * as api from "../config/api";

function run(runId: number, title: string) {
    return {
        runId, title, subjectId: 1, subjectName: "Subject", definitionId: 2,
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
        items: [run(1, "Run A"), run(2, "Run B")], totalCount: 2, page: 1, limit: 20,
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
    it("renders run titles and the chip filter input instead of separate controls", async () => {
        renderWithRouter(<WorkflowRunsPage />);
        expect(await screen.findByText("Run A")).toBeInTheDocument();
        expect(screen.getByText("Run B")).toBeInTheDocument();
        expect(screen.queryByLabelText("Status")).toBeNull();
        expect(screen.queryByPlaceholderText("Subject ID")).toBeNull();
        expect(screen.queryByText("No workflow runs match the current filters.")).toBeNull();
    });

    it("adds status and workflow chips from the table and filters by them", async () => {
        renderWithRouter(<WorkflowRunsPage />);
        await screen.findByText("Run A");
        await userEvent.click(screen.getAllByText("completed")[0]);
        await waitFor(() => expect(lastFilters().status).toBe("completed"));
        await userEvent.click(screen.getAllByRole("button", { name: "Triage" })[0]);
        await waitFor(() => expect(lastFilters().definitionId).toBe(2));
        expect(screen.getByText("Filters")).toBeInTheDocument();
        await userEvent.click(screen.getByRole("button", { name: "Close label group Filters" }));
        await waitFor(() => expect(lastFilters()).toEqual(expect.objectContaining({ status: undefined, definitionId: undefined })));
    });

    it("normalises and validates typed filter values", () => {
        expect(normalizeRunFilter("status", " Running ")).toBe("running");
        expect(normalizeRunFilter("subject", "12")).toBe("12");
        expect(normalizeRunFilter("subject", "abc")).toBeNull();
        expect(normalizeRunFilter("label", "team:core")).toBe("team:core");
        expect(normalizeRunFilter("label", "team")).toBeNull();
        expect(normalizeRunFilter("workflow", "  ")).toBeNull();
    });

    it("resolves a workflow filter by name or id, or to no match", () => {
        const defs = [{ id: 2, name: "Triage" }] as never;
        expect(resolveWorkflowFilter("triage", defs)).toBe(2);
        expect(resolveWorkflowFilter("7", defs)).toBe(7);
        expect(resolveWorkflowFilter("nope", defs)).toBe(-1);
    });
});
