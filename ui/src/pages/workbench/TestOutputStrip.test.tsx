import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import type { CompositionTestRun } from "../../config/compositions";
import { runSummary, TestOutputStrip } from "./TestOutputStrip";

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

    it("opens the item from its name without opening the details", () => {
        const onOpenItem = vi.fn();
        render(<TestOutputStrip runs={runs} collapsed={false} onToggle={vi.fn()} onOpenItem={onOpenItem} />);
        fireEvent.click(screen.getByRole("button", { name: "greet" }));
        expect(onOpenItem).toHaveBeenCalledWith(11);
        expect(screen.queryByRole("dialog")).toBeNull();
    });

    it("opens a details dialog when a row is clicked or activated with the keyboard", () => {
        render(<TestOutputStrip runs={runs} collapsed={false} onToggle={vi.fn()} onOpenItem={vi.fn()} />);
        fireEvent.click(screen.getByRole("button", { name: "Show details of the greet test run" }));
        expect(screen.getByRole("dialog")).toBeTruthy();
        expect(screen.getByText("Test run: greet")).toBeTruthy();
        fireEvent.click(screen.getAllByRole("button", { name: "Close" }).pop() as HTMLElement);
        expect(screen.queryByRole("dialog")).toBeNull();
        fireEvent.keyDown(screen.getByRole("button", { name: "Show details of the list-labels test run" }), { key: "Enter" });
        expect(screen.getByText("Test run: list-labels")).toBeTruthy();
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
        expect(screen.queryByRole("dialog")).toBeNull();
        fireEvent.click(screen.getByRole("button", { name: "Run the suggested list-labels test" }));
        expect(onRunSuggestion).toHaveBeenCalledTimes(2);
        expect(screen.queryByRole("dialog")).toBeNull();
    });

    it("summarizes a failed run with no problems by its last stderr line", () => {
        expect(runSummary({ id: "f", itemId: 1, itemType: "action-type", itemName: "x", author: "user",
            status: "failed", startedAt: "", exitCode: 1, problems: [], stderr: "first\nError: HTTP 404: Not Found\n    at run (action.mjs:3:9)\n\n" }))
            .toBe("Error: HTTP 404: Not Found");
    });

    it("summarizes a passed tool run by its last stdout line", () => {
        expect(runSummary({ id: "t", itemId: 1, itemType: "tool", itemName: "x", author: "user",
            status: "passed", startedAt: "", exitCode: 0, problems: [], stdout: "counting\n4\n" })).toBe("4");
    });
});

describe("TestOutputStrip with Tests and Runs tabs", () => {
    const tabsProps = (active: "tests" | "runs", onSelect = vi.fn()) => ({
        active, onSelect, runsCount: 4, runsContent: <p>runs-body</p>,
        runsActions: <button type="button">Refresh</button>,
    });

    it("shows tabs with counts and the tests tab content and actions", () => {
        render(<TestOutputStrip runs={runs} collapsed={false} onToggle={vi.fn()} onOpenItem={vi.fn()}
            actions={<button type="button">Test…</button>} bottomTabs={tabsProps("tests")} />);
        expect(screen.getByRole("tab", { name: /Tests\s*2/ })).toBeTruthy();
        expect(screen.getByRole("tab", { name: /Runs\s*4/ })).toBeTruthy();
        expect(screen.getByRole("button", { name: "Test…" })).toBeTruthy();
        expect(screen.queryByRole("button", { name: "Refresh" })).toBeNull();
        expect(screen.getByRole("button", { name: "greet" })).toBeTruthy();
    });

    it("shows the runs tab content and actions, and reports tab switches", () => {
        const onSelect = vi.fn();
        render(<TestOutputStrip runs={runs} collapsed={false} onToggle={vi.fn()} onOpenItem={vi.fn()}
            actions={<button type="button">Test…</button>} bottomTabs={tabsProps("runs", onSelect)} />);
        expect(screen.getByText("runs-body")).toBeTruthy();
        expect(screen.getByRole("button", { name: "Refresh" })).toBeTruthy();
        expect(screen.queryByRole("button", { name: "Test…" })).toBeNull();
        expect(screen.queryByRole("button", { name: "greet" })).toBeNull();
        fireEvent.click(screen.getByRole("tab", { name: /Tests/ }));
        expect(onSelect).toHaveBeenCalledWith("tests");
    });
});
