import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import type { CompositionTestRun } from "../../config/compositions";
import { prettyJson, TestRunDetailModal } from "./TestRunDetailModal";

const run: CompositionTestRun = { id: "r", itemId: 7, itemType: "action-type", itemName: "greet", author: "user",
    status: "failed", startedAt: "2026-10-13T10:00:00Z", durationMs: 12, exitCode: 0,
    inputs: { who: "bob" }, outputs: { a: 1 }, problems: ["Output: Missing required field 'b'."],
    stdout: '{"a":1}', stderr: "plain warning" };

describe("TestRunDetailModal", () => {
    it("pretty-prints only JSON objects and arrays", () => {
        expect(prettyJson('{"a":[1]}')).toBe('{\n  "a": [\n    1\n  ]\n}');
        expect(prettyJson("4")).toBeNull();
        expect(prettyJson("{oops")).toBeNull();
        expect(prettyJson(undefined)).toBeNull();
    });

    it("shows status, problems, highlighted JSON and plain text", () => {
        render(<TestRunDetailModal run={run} onClose={vi.fn()} onOpenItem={vi.fn()} />);
        expect(screen.getByText("failed")).toBeTruthy();
        expect(screen.getByText("Output: Missing required field 'b'.")).toBeTruthy();
        expect(screen.getByLabelText("Inputs").getAttribute("data-language")).toBe("json");
        expect(screen.getByLabelText("Inputs").querySelector("code.language-json")).toBeTruthy();
        expect(screen.getByLabelText("Inputs").textContent).toBe('{\n  "who": "bob"\n}');
        expect(screen.getByLabelText("stdout").getAttribute("data-language")).toBe("json");
        expect(screen.getByLabelText("stderr").getAttribute("data-language")).toBe("plaintext");
        expect(screen.getByLabelText("stderr").textContent).toBe("plain warning");
    });

    it("opens the item and offers Run… for a suggestion", () => {
        const onOpenItem = vi.fn();
        const onClose = vi.fn();
        const onRunSuggestion = vi.fn();
        const suggested: CompositionTestRun = { ...run, status: "suggested", author: "ai", exitCode: undefined,
            outputs: undefined, problems: undefined, stdout: undefined, stderr: undefined };
        render(<TestRunDetailModal run={suggested} onClose={onClose} onOpenItem={onOpenItem}
            onRunSuggestion={onRunSuggestion} />);
        fireEvent.click(screen.getByRole("button", { name: "greet" }));
        expect(onOpenItem).toHaveBeenCalledWith(7);
        fireEvent.click(screen.getByRole("button", { name: "Run…" }));
        expect(onRunSuggestion).toHaveBeenCalledWith(suggested);
    });
});
