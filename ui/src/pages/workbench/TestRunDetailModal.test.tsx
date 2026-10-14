import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import type { CompositionTestRun } from "../../config/compositions";
import { prettyJson, sourceTab, TestRunDetailModal } from "./TestRunDetailModal";

const run: CompositionTestRun = { id: "r", itemId: 7, itemType: "action-type", itemName: "greet", author: "user",
    status: "failed", startedAt: "2026-10-13T10:00:00Z", durationMs: 12, exitCode: 0,
    inputs: { who: "bob" }, outputs: { a: 1 }, problems: ["Output: Missing required field 'b'."],
    stdout: '{"a":1}', stderr: "plain warning" };

/** The code block labelled `label` (tab panels carry the same label as their tab). */
function block(label: string): HTMLElement {
    const found: HTMLElement | undefined = screen.getAllByLabelText(label).find((e) => e.hasAttribute("data-language"));
    if (!found) {
        throw new Error(`No code block labelled ${label}`);
    }
    return found;
}

describe("TestRunDetailModal", () => {
    it("pretty-prints only JSON objects and arrays", () => {
        expect(prettyJson('{"a":[1]}')).toBe('{\n  "a": [\n    1\n  ]\n}');
        expect(prettyJson("4")).toBeNull();
        expect(prettyJson("{oops")).toBeNull();
        expect(prettyJson(undefined)).toBeNull();
    });

    it("shows problems and opens a failed run on Stdout/err, with highlighted JSON and plain text", () => {
        render(<TestRunDetailModal run={run} onClose={vi.fn()} onOpenItem={vi.fn()} />);
        expect(screen.getByText("failed")).toBeTruthy();
        expect(screen.getByText("Output: Missing required field 'b'.")).toBeTruthy();
        expect(screen.getByRole("tab", { name: "Stdout/err" }).getAttribute("aria-selected")).toBe("true");
        expect(block("stdout").getAttribute("data-language")).toBe("json");
        expect(block("stderr").getAttribute("data-language")).toBe("plaintext");
        expect(block("stderr").textContent).toBe("plain warning");
        fireEvent.click(screen.getByRole("tab", { name: "Inputs" }));
        expect(block("Inputs").querySelector("code.language-json")).toBeTruthy();
        expect(block("Inputs").textContent).toBe('{\n  "who": "bob"\n}');
        fireEvent.click(screen.getByRole("tab", { name: "Outputs" }));
        expect(block("Outputs").textContent).toBe('{\n  "a": 1\n}');
    });

    it("shows a Script tab for script action types and a Code tab for node action types", () => {
        const { unmount } = render(<TestRunDetailModal run={{ ...run, executionMode: "script", resolvedScript: "echo hi" }}
            onClose={vi.fn()} onOpenItem={vi.fn()} />);
        expect(screen.getByRole("tab", { name: "Script" })).toBeTruthy();
        expect(screen.queryByRole("tab", { name: "Code" })).toBeNull();
        unmount();
        render(<TestRunDetailModal run={{ ...run, executionMode: "node", resolvedScript: "export async function run() {}" }}
            onClose={vi.fn()} onOpenItem={vi.fn()} />);
        fireEvent.click(screen.getByRole("tab", { name: "Code" }));
        expect(block("Code").getAttribute("data-language")).toBe("javascript");
    });

    it("chooses the source tab from the execution mode", () => {
        expect(sourceTab({ ...run, resolvedScript: undefined })).toBeNull();
        expect(sourceTab({ ...run, itemType: "tool", resolvedScript: "x" })?.title).toBe("Script");
        expect(sourceTab({ ...run, executionMode: "node", resolvedScript: "x" })?.title).toBe("Code");
    });

    it("has no Outputs tab for tools", () => {
        render(<TestRunDetailModal run={{ ...run, itemType: "tool", outputs: undefined, resolvedScript: "echo" }}
            onClose={vi.fn()} onOpenItem={vi.fn()} />);
        expect(screen.queryByRole("tab", { name: "Outputs" })).toBeNull();
        expect(screen.getByRole("tab", { name: "Parameters" })).toBeTruthy();
        expect(screen.getByRole("tab", { name: "Script" })).toBeTruthy();
    });

    it("opens a passed action type run on Outputs", () => {
        render(<TestRunDetailModal run={{ ...run, status: "passed", problems: [] }} onClose={vi.fn()} onOpenItem={vi.fn()} />);
        expect(screen.getByRole("tab", { name: "Outputs" }).getAttribute("aria-selected")).toBe("true");
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
