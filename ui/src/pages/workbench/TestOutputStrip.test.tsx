import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import type { CompositionTestRun } from "../../config/compositions";
import { TestOutputStrip } from "./TestOutputStrip";

const runs: CompositionTestRun[] = [
    { id: "b", itemId: 11, itemType: "action-type", itemName: "greet", author: "ai", status: "failed",
        startedAt: "2026-10-13T10:00:00Z", durationMs: 40, exitCode: 0, outputs: {},
        problems: ["Output: Missing required field 'greeting'."], stdout: "{}", stderr: "warn" },
    { id: "a", itemId: 12, itemType: "tool", itemName: "list-labels", author: "user", status: "passed",
        startedAt: "2026-10-13T09:59:00Z", durationMs: 5, exitCode: 0, problems: [], stdout: "bug\n", stderr: "" },
];

describe("TestOutputStrip", () => {
    it("lists runs with status, author and summary", () => {
        render(<TestOutputStrip runs={runs} collapsed={false} onToggle={vi.fn()} onOpenItem={vi.fn()} />);
        expect(screen.getByText("Test output")).toBeTruthy();
        expect(screen.getByText("2")).toBeTruthy();
        expect(screen.getByLabelText("failed")).toBeTruthy();
        expect(screen.getByLabelText("passed")).toBeTruthy();
        expect(screen.getAllByText("AI")).toHaveLength(1);
        expect(screen.getByText("Output: Missing required field 'greeting'.")).toBeTruthy();
    });

    it("opens the item and expands details", () => {
        const onOpenItem = vi.fn();
        render(<TestOutputStrip runs={runs} collapsed={false} onToggle={vi.fn()} onOpenItem={onOpenItem} />);
        fireEvent.click(screen.getByRole("button", { name: "greet" }));
        expect(onOpenItem).toHaveBeenCalledWith(11);
        fireEvent.click(screen.getAllByRole("button", { name: "Show details" })[0]);
        expect(screen.getByText("warn")).toBeTruthy();
    });

    it("hides the list when collapsed", () => {
        const onToggle = vi.fn();
        render(<TestOutputStrip runs={runs} collapsed onToggle={onToggle} onOpenItem={vi.fn()} />);
        expect(screen.queryByRole("button", { name: "greet" })).toBeNull();
        fireEvent.click(screen.getByRole("button", { name: "Expand test output" }));
        expect(onToggle).toHaveBeenCalled();
    });

    it("renders a suggested run with Run…", () => {
        const onRunSuggestion = vi.fn();
        const suggested: CompositionTestRun = { id: "s", itemId: 12, itemType: "tool", itemName: "list-labels",
            author: "ai", status: "suggested", startedAt: "2026-10-13T10:01:00Z", inputs: { repo: "x" } };
        render(<TestOutputStrip runs={[suggested]} collapsed={false} onToggle={vi.fn()} onOpenItem={vi.fn()}
            onRunSuggestion={onRunSuggestion} />);
        expect(screen.getByLabelText("suggested")).toBeTruthy();
        expect(screen.getByText("Suggested by AI")).toBeTruthy();
        expect(screen.queryByText("AI")).toBeNull();
        expect(screen.getByText('Inputs: {"repo":"x"}')).toBeTruthy();
        fireEvent.click(screen.getByRole("button", { name: "Run…" }));
        expect(onRunSuggestion).toHaveBeenCalledWith(suggested);
    });
});
