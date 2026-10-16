import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import * as api from "../config/api";
import { Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { SubjectDetailPage } from "./SubjectDetailPage";
import { renderWithRouter } from "../test/renderWithRouter";

function run(runId: number, title: string, startedOn: string) {
    return {
        runId, title, subjectId: 7, definitionId: 2, definitionName: "Triage",
        definitionVersion: 1, status: "completed", startedOn,
    };
}

vi.mock("../config/api", () => ({
    fetchSubject: vi.fn().mockResolvedValue({
        id: 7, name: "Issue 42", type: "issue", ref: "42",
        createdOn: "2026-10-01T00:00:00Z", updatedOn: "2026-10-01T00:00:00Z",
        labels: [], active: false,
    }),
    fetchSubjectTasks: vi.fn().mockResolvedValue([]),
    fetchThreadEntries: vi.fn().mockResolvedValue([]),
    fetchSubjectMetrics: vi.fn().mockResolvedValue(null),
    fetchAgents: vi.fn().mockResolvedValue([]),
    fetchStreamEvents: vi.fn().mockResolvedValue({ items: [] }),
    fetchWorkflowRuns: vi.fn().mockResolvedValue({
        items: [run(1, "First run", "2026-10-01T00:00:00Z"),
            { ...run(2, "Second run", "2026-10-02T00:00:00Z"), status: "failed" }],
        totalCount: 2, page: 1, limit: 100,
    }),
    rerunWorkflowRun: vi.fn().mockResolvedValue({ id: 9, runId: 9 }),
    deleteSubject: vi.fn(),
    updateSubject: vi.fn(),
    updateSubjectBody: vi.fn(),
    formatBytes: vi.fn(() => "0 B"),
    fetchInboxItem: vi.fn(),
    completeInboxItem: vi.fn(),
}));
vi.mock("../components/assistant/CreateSessionModal", () => ({ CreateSessionModal: () => null }));

describe("SubjectDetailPage", () => {
    it("shows the subject's runs in the timeline", async () => {
        renderWithRouter(
            <Routes><Route path="/subjects/:subjectId" element={<SubjectDetailPage />} /></Routes>,
            "/subjects/7");
        expect(await screen.findByText("First run")).toBeInTheDocument();
        expect(screen.getByText("Second run")).toBeInTheDocument();
        expect(screen.getByText("Inactive")).toBeInTheDocument();
        expect(screen.queryByRole("tab", { name: /^Workflow/ })).toBeNull();
    });

    it("re-runs a failed run after confirmation and links to the new run", async () => {
        renderWithRouter(
            <Routes><Route path="/subjects/:subjectId" element={<SubjectDetailPage />} /></Routes>,
            "/subjects/7");
        await screen.findByText("Second run");
        expect(screen.queryByRole("button", { name: "Re-run run #1" })).toBeNull();
        await userEvent.click(screen.getByRole("button", { name: "Re-run run #2" }));
        expect(await screen.findByText("Re-run workflow run")).toBeInTheDocument();
        await userEvent.click(screen.getByRole("button", { name: "Re-run" }));
        await waitFor(() => expect(api.rerunWorkflowRun).toHaveBeenCalledWith(2));
        expect(await screen.findByText("Started run #9 as a re-run of run #2.")).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "View run #9" }).getAttribute("href")).toBe("/runs/9");
    });
});
