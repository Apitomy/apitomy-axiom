import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import type { WorkflowRunSummary } from "../../config/api";
import { RunsList } from "./RunsList";

const runs = [
    { runId: 9, status: "failed", title: "Triage", definitionVersion: 3, startedOn: "2026-10-14T10:00:00Z",
        currentNodeName: "classify" },
    { runId: 8, status: "completed", title: "Done", definitionVersion: 2, startedOn: "2026-10-14T09:00:00Z",
        currentNodeName: "end" },
] as WorkflowRunSummary[];

describe("RunsList", () => {
    it("lists runs with status, id, title, version and node of failed runs", () => {
        render(<RunsList runs={runs} published onOpenRun={vi.fn()} onAskAi={vi.fn()} />);
        expect(screen.getByText("failed")).toBeTruthy();
        expect(screen.getByText("#9")).toBeTruthy();
        expect(screen.getByText("Triage")).toBeTruthy();
        expect(screen.getByText("v3")).toBeTruthy();
        expect(screen.getByText(/classify/)).toBeTruthy();
        expect(screen.queryByText(/· end/)).toBeNull();
    });

    it("opens a run on click and asks the AI about failed runs", () => {
        const onOpenRun = vi.fn();
        const onAskAi = vi.fn();
        render(<RunsList runs={runs} published onOpenRun={onOpenRun} onAskAi={onAskAi} />);
        expect(screen.getAllByRole("button", { name: "Ask AI to analyze" })).toHaveLength(1);
        fireEvent.click(screen.getByRole("button", { name: "Ask AI to analyze" }));
        expect(onAskAi).toHaveBeenCalledWith(runs[0]);
        expect(onOpenRun).not.toHaveBeenCalled();
        fireEvent.click(screen.getByText("Done"));
        expect(onOpenRun).toHaveBeenCalledWith(8);
    });

    it("shows empty and error states", () => {
        const { rerender } = render(<RunsList runs={[]} published={false} onOpenRun={vi.fn()} onAskAi={vi.fn()} />);
        expect(screen.getByText("Publish the workflow to see its runs here.")).toBeTruthy();
        rerender(<RunsList runs={[]} published onOpenRun={vi.fn()} onAskAi={vi.fn()} />);
        expect(screen.getByText("No runs of this workflow yet.")).toBeTruthy();
        rerender(<RunsList runs={[]} published error="boom" onOpenRun={vi.fn()} onAskAi={vi.fn()} />);
        expect(screen.getByText(/boom/)).toBeTruthy();
    });
});
