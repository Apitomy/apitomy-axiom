import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import {
    AddItemDialog, askAiAboutItemPrefill, CompositionTree, RemoveItemDialog, statusTooltip,
} from "./CompositionTree";
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

function renderTree(onOpen = vi.fn(), onRemove = vi.fn(), onTest = vi.fn()) {
    render(<CompositionTree composition={composition} items={items} problems={problems} lastCompletedTurnId="t1"
        selectedItemId={undefined} onOpen={onOpen} onRemove={onRemove} onTest={onTest} />);
    return onOpen;
}

describe("CompositionTree", () => {
    afterEach(() => vi.restoreAllMocks());

    it("orders groups and rows workflow, action types, tools", () => {
        renderTree();
        const headings = [...document.querySelectorAll(".pf-v6-c-simple-list__title")].map((h) => h.textContent);
        expect(headings).toEqual(["Workflow", "Action types", "Tools", "Subscriptions"]);
        const rows = screen.getAllByRole("button", { name: /^(wf|at|tl)\b/ }).map((b) => b.getAttribute("data-name"));
        expect(rows).toEqual(["wf", "at", "tl"]);
    });

    it("shows status icons, AI label and marks unpublished names", () => {
        renderTree();
        expect(screen.getByLabelText("valid")).toBeTruthy();
        expect(screen.getByLabelText("warnings")).toBeTruthy();
        expect(screen.getByLabelText("errors")).toBeTruthy();
        expect(screen.getAllByText("AI")).toHaveLength(1);
        expect(document.querySelectorAll(".axiom-workbench__item-name--unpublished")).toHaveLength(1);
        expect(screen.getByText("(unpublished changes)", { exact: false })).toBeTruthy();
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

    it("offers Remove on action type and tool rows only", async () => {
        const onRemove = vi.fn();
        renderTree(vi.fn(), onRemove);
        expect(screen.queryByLabelText("Actions for wf")).toBeNull();
        fireEvent.click(screen.getByLabelText("Actions for tl"));
        fireEvent.click(await screen.findByRole("menuitem", { name: "Remove" }));
        expect(onRemove).toHaveBeenCalledWith(3);
    });

    it("offers Test… on action type and tool rows", async () => {
        const onTest = vi.fn();
        renderTree(vi.fn(), vi.fn(), onTest);
        fireEvent.click(screen.getByLabelText("Actions for at"));
        fireEvent.click(await screen.findByRole("menuitem", { name: "Test…" }));
        expect(onTest).toHaveBeenCalledWith(2);
    });

    it("groups subscriptions and offers no Test… on their rows", async () => {
        const sub = { id: 4, type: "subscription", name: "sub", contentRevision: "r", lastAuthor: "user", changed: true } as c.CompositionItem;
        render(<CompositionTree composition={composition} items={[...items, sub]} problems={problems} lastCompletedTurnId="t1"
            selectedItemId={undefined} onOpen={vi.fn()} onRemove={vi.fn()} onTest={vi.fn()} />);
        const headings = [...document.querySelectorAll(".pf-v6-c-simple-list__title")].map((h) => h.textContent);
        expect(headings).toContain("Subscriptions");
        fireEvent.click(screen.getByLabelText("Actions for sub"));
        expect(await screen.findByRole("menuitem", { name: "Remove" })).toBeTruthy();
        expect(screen.queryByRole("menuitem", { name: /Test/ })).toBeNull();
    });

    it("adds a new subscription by name only", () => {
        const fetchTools = vi.spyOn(api, "fetchTools");
        const fetchActionTypes = vi.spyOn(api, "fetchActionTypes");
        const onSubmit = vi.fn();
        render(<AddItemDialog type="subscription" onSubmit={onSubmit} onClose={vi.fn()} />);
        expect(screen.queryByText("Existing")).toBeNull();
        fireEvent.change(screen.getByLabelText("Name"), { target: { value: "s1" } });
        fireEvent.click(screen.getByRole("button", { name: "Add" }));
        expect(onSubmit).toHaveBeenCalledWith({ type: "subscription", name: "s1" });
        expect(fetchTools).not.toHaveBeenCalled();
        expect(fetchActionTypes).not.toHaveBeenCalled();
    });

    it("offers Ask AI about this… on rows, disabled while the AI works", async () => {
        const onAskAi = vi.fn();
        const { rerender } = render(<CompositionTree composition={composition} items={items} problems={problems}
            lastCompletedTurnId="t1" selectedItemId={undefined} onOpen={vi.fn()} onAskAi={onAskAi} />);
        fireEvent.click(screen.getByLabelText("Actions for tl"));
        fireEvent.click(await screen.findByRole("menuitem", { name: "Ask AI about this…" }));
        expect(onAskAi).toHaveBeenCalledWith(items[0]);
        rerender(<CompositionTree composition={{ ...composition, turnState: "ai-working" }} items={items}
            problems={problems} lastCompletedTurnId="t1" selectedItemId={undefined} onOpen={vi.fn()} onAskAi={onAskAi} />);
        fireEvent.click(screen.getByLabelText("Actions for at"));
        const item = await screen.findByRole("menuitem", { name: "Ask AI about this…" });
        expect(item.getAttribute("aria-disabled") === "true" || (item as HTMLButtonElement).disabled).toBe(true);
    });

    it("prefills the chat with the item's kind and name", () => {
        expect(askAiAboutItemPrefill(items[0])).toBe("About tool `tl`: ");
        expect(askAiAboutItemPrefill(items[1])).toBe("About action type `at`: ");
    });

    it("warns when the removed item is still in use", () => {
        const onConfirm = vi.fn();
        render(<RemoveItemDialog item={items[1]} usages={["Workflow wf (1 node)"]} onConfirm={onConfirm}
            onClose={vi.fn()} />);
        expect(screen.getByText("This action type is still in use")).toBeTruthy();
        expect(screen.getByText("Workflow wf (1 node)")).toBeTruthy();
        fireEvent.click(screen.getByRole("button", { name: "Remove" }));
        expect(onConfirm).toHaveBeenCalled();
    });
});
