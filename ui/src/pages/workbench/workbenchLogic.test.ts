import { describe, expect, it } from "vitest";
import {
    defaultPublishChoice, isLockedForUser, itemStatus, mergeCatalog, publishSelection, referencedActionTypes,
    referencedTools, undoPlan,
} from "./workbenchLogic";
import type { Composition, CompositionItem } from "../../config/compositions";

const item = (id: number, type: CompositionItem["type"], name: string, extra: Partial<CompositionItem> = {}): CompositionItem =>
    ({ id, type, name, contentRevision: "r", lastAuthor: "user", changed: true, ...extra });

describe("workbenchLogic", () => {
    it("derives item status from problems", () => {
        expect(itemStatus(undefined)).toBe("unknown");
        expect(itemStatus([])).toBe("valid");
        expect(itemStatus([{ severity: "warning", code: "c", message: "m" }])).toBe("warnings");
        expect(itemStatus([{ severity: "warning", code: "c", message: "m" }, { severity: "error", code: "c", message: "m" }])).toBe("errors");
    });

    it("locks only non-workflow items touched by the running turn", () => {
        const composition = { turnState: "ai-working", currentTurnId: "t1" } as Composition;
        expect(isLockedForUser(item(1, "tool", "a", { lastTurnId: "t1" }), composition)).toBe(true);
        expect(isLockedForUser(item(2, "tool", "b", { lastTurnId: "t0" }), composition)).toBe(false);
        expect(isLockedForUser(item(3, "workflow", "w", { lastTurnId: "t1" }), composition)).toBe(false);
        expect(isLockedForUser(item(1, "tool", "a", { lastTurnId: "t1" }), { ...composition, turnState: "idle" })).toBe(false);
    });

    it("finds references", () => {
        expect(referencedActionTypes({ id: "w", name: "w", edges: [], nodes: [
            { id: "a", type: "action", name: "A", config: { actionType: "triage" } },
            { id: "b", type: "action", name: "B", config: { actionType: "sdk:x" } },
            { id: "c", type: "action", name: "C", config: { actionType: "triage" } },
        ] } as never)).toEqual(["triage"]);
        expect(referencedTools({ allowedTools: "Read, mcp__axiom-tools__gh mcp__axiom-tools__jq" })).toEqual(["gh", "jq"]);
        expect(referencedTools({ allowedTools: ["mcp__axiom-tools__x"] })).toEqual(["x"]);
    });

    it("selects changed draft dependencies transitively", () => {
        const items = [item(1, "workflow", "wf"), item(2, "action-type", "at"), item(3, "tool", "gh"),
            item(4, "tool", "unchanged", { changed: false }), item(5, "action-type", "other")];
        const contents = {
            1: { nodes: [{ id: "a", type: "action", config: { actionType: "at" } }], edges: [] },
            2: { name: "at", allowedTools: "mcp__axiom-tools__gh mcp__axiom-tools__unchanged" },
        };
        expect(publishSelection(items, contents, [1])).toEqual([1, 2, 3]);
        expect(defaultPublishChoice(items, { 1: "valid", 2: "errors", 3: "valid", 4: "valid", 5: "warnings" })).toEqual([1, 3, 5]);
    });

    it("plans undo of the workflow only when the turn's change is the latest save", () => {
        const before = { id: "w", name: "w", nodes: [], edges: [] };
        const result = { restored: [], removed: [], skipped: [], workflowBefore: before, workflowRestorable: true };
        expect(undoPlan(result as never, "t1", "t1").replaceWorkflow).toBe(before);
        expect(undoPlan(result as never, "t2", "t1").replaceWorkflow).toBeNull();
        expect(undoPlan({ ...result, workflowRestorable: false } as never, "t1", "t1").replaceWorkflow).toBeNull();
    });

    it("merges draft action types into the catalog", () => {
        const merged = mergeCatalog(
            [{ value: "a", label: "a" }, { value: "b", label: "b", description: "db" }],
            [{ name: "b", content: { name: "b", inputs: [{ name: "x", type: "string" }] } }, { name: "c", content: { name: "c" } }]);
        expect(merged.map((d) => d.value)).toEqual(["a", "b", "c"]);
        expect(merged[1].inputs).toEqual([{ name: "x", type: "string" }]);
    });
});
