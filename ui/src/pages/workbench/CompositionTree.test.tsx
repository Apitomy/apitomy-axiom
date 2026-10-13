import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { CompositionTree } from "./CompositionTree";
import * as c from "../../config/compositions";

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
        selectedItemId={undefined} onOpen={onOpen} onAdd={() => {}} />);
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
});
