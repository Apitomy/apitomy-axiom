import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { AddItemDialog, CompositionTree, statusTooltip } from "./CompositionTree";
import * as c from "../../config/compositions";
import * as api from "../../config/api";

const composition = { id: 9, name: "c", turnState: "idle", applyMode: "live", createdAt: "", updatedAt: "" } as c.Composition;
const items = [
    { id: 3, type: "tool", name: "tl", contentRevision: "r", lastAuthor: "user", changed: false },
    { id: 2, type: "action-type", name: "at", contentRevision: "r", lastAuthor: "ai", lastTurnId: "t1", changed: true },
    { id: 1, type: "workflow", name: "wf", contentRevision: "r", lastAuthor: "user", changed: false },
] as c.CompositionItem[];
const problems = {
    1: [],
    2: [{ severity: "warning", code: "w", message: "w" }],
    3: [{ severity: "error", code: "e", message: "e" }],
} as Record<number, c.ConfigProblem[]>;

function renderTree(onOpen = vi.fn()) {
    render(<CompositionTree composition={composition} items={items} problems={problems} lastCompletedTurnId="t1"
        selectedItemId={undefined} onOpen={onOpen} />);
    return onOpen;
}

describe("CompositionTree", () => {
    afterEach(() => vi.restoreAllMocks());

    it("orders groups and rows workflow, action types, tools", () => {
        renderTree();
        const headings = [...document.querySelectorAll(".pf-v6-c-simple-list__title")].map((h) => h.textContent);
        expect(headings).toEqual(["Workflow", "Action types", "Tools"]);
        const rows = screen.getAllByRole("button", { name: /^(wf|at|tl)\b/ }).map((b) => b.getAttribute("data-name"));
        expect(rows).toEqual(["wf", "at", "tl"]);
    });

    it("shows status icons, AI label and unpublished dot", () => {
        renderTree();
        expect(screen.getByLabelText("valid")).toBeTruthy();
        expect(screen.getByLabelText("warnings")).toBeTruthy();
        expect(screen.getByLabelText("errors")).toBeTruthy();
        expect(screen.getAllByText("AI")).toHaveLength(1);
        expect(screen.getAllByLabelText("Unpublished changes")).toHaveLength(1);
    });

    it("opens a row on click", () => {
        const onOpen = renderTree();
        fireEvent.click(screen.getByRole("button", { name: /^at\b/ }));
        expect(onOpen).toHaveBeenCalledWith(2);
    });

    it("describes the status with problem counts", () => {
        expect(statusTooltip([])).toBe("Valid");
        expect(statusTooltip([{ severity: "warning", code: "w", message: "w" },
            { severity: "warning", code: "w", message: "w" }])).toBe("2 warnings. Publishing is still allowed.");
        expect(statusTooltip([{ severity: "error", code: "e", message: "e" },
            { severity: "warning", code: "w", message: "w" }]))
            .toBe("1 error, 1 warning. Fix the errors before publishing.");
    });

    it("asks for the execution mode of a new action type", () => {
        vi.spyOn(api, "fetchActionTypes").mockResolvedValue({ items: [], count: 0 } as never);
        const onSubmit = vi.fn();
        render(<AddItemDialog type="action-type" onSubmit={onSubmit} onClose={vi.fn()} />);
        fireEvent.change(screen.getByLabelText("Name"), { target: { value: "x" } });
        fireEvent.change(screen.getByLabelText("Execution mode"), { target: { value: "script" } });
        fireEvent.click(screen.getByRole("button", { name: "Add" }));
        expect(onSubmit).toHaveBeenCalledWith({ type: "action-type", name: "x", executionMode: "script" });
    });

    it("switches between new and existing with a toggle group", async () => {
        vi.spyOn(api, "fetchActionTypes").mockResolvedValue({ items: [{ id: 7, name: "old" }], count: 1 } as never);
        const onSubmit = vi.fn();
        render(<AddItemDialog type="action-type" onSubmit={onSubmit} onClose={vi.fn()} />);
        expect(screen.getByRole("button", { name: "New" }).getAttribute("aria-pressed")).toBe("true");
        fireEvent.click(screen.getByRole("button", { name: "Existing" }));
        expect(screen.getByRole("button", { name: "Existing" }).getAttribute("aria-pressed")).toBe("true");
        await screen.findByRole("option", { name: "old" });
        fireEvent.click(screen.getByRole("button", { name: "Add" }));
        expect(onSubmit).toHaveBeenCalledWith({ type: "action-type", existingItemId: 7 });
    });

    it("does not ask for an execution mode for a new tool", () => {
        vi.spyOn(api, "fetchTools").mockResolvedValue({ items: [], count: 0 } as never);
        const onSubmit = vi.fn();
        render(<AddItemDialog type="tool" onSubmit={onSubmit} onClose={vi.fn()} />);
        expect(screen.queryByLabelText("Execution mode")).toBeNull();
        fireEvent.change(screen.getByLabelText("Name"), { target: { value: "t" } });
        fireEvent.click(screen.getByRole("button", { name: "Add" }));
        expect(onSubmit).toHaveBeenCalledWith({ type: "tool", name: "t" });
    });
});
