import { describe, expect, it } from "vitest";
import { describeToolCall, groupToolCalls, workbenchTool, type ToolCallRef } from "./toolCards";

describe("workbenchTool", () => {
    it("recognises both prefixes", () => {
        expect(workbenchTool("mcp__workbench__upsert_item")).toBe("upsert_item");
        expect(workbenchTool("workbench_axiom_list_tools")).toBe("axiom_list_tools");
    });
    it("rejects other tools", () => {
        expect(workbenchTool("Bash")).toBeNull();
        expect(workbenchTool("mcp__workbench__nope")).toBeNull();
        expect(workbenchTool("upsert_item")).toBeNull();
    });
});

const ops = [
    { op: "addNode", node: { id: "n1", name: "Fetch issue" } },
    { op: "addEdge", edge: { id: "e1", source: "start", target: "n1" } },
    { op: "removeNode", id: "old" },
    { op: "updateNode", id: "n2" },
];

describe("describeToolCall", () => {
    const cases: Array<[string, string, Record<string, unknown> | undefined, string, Record<string, unknown>]> = [
        ["upsert created", "mcp__workbench__upsert_item", { type: "action-type" },
            "VALID: action-type/fetch (created, revision sha256:abc)",
            { text: "Created action type fetch", status: "VALID", readOnly: false,
                target: { kind: "item", itemType: "action-type", name: "fetch" } }],
        ["upsert updated warnings", "workbench_upsert_item", {},
            "VALID with warnings: tool/my tool (updated, revision sha256:abc)\n- warn",
            { text: "Updated tool my tool", status: "VALID with warnings" }],
        ["upsert invalid legacy", "workbench_upsert_item", {},
            "INVALID: workflow/wf (revision sha256:abc)",
            { text: "Updated workflow wf", status: "INVALID" }],
        ["remove", "workbench_remove_item", { type: "tool", name: "t" }, "Removed tool/t.",
            { text: "Removed tool t" }],
        ["propose applied", "workbench_propose_workflow_changes", { changeSet: { ops } },
            "APPLIED. New revision: sha256:x",
            { text: "Proposed 4 workflow changes added Fetch issue, connected start → n1, removed old and 1 more",
                target: { kind: "workflow", nodeId: "n1" }, variant: "default" }],
        ["propose applied json string", "workbench_propose_workflow_changes",
            { changeSet: JSON.stringify({ ops: [{ op: "removeEdge", id: "e9" }] }) }, "APPLIED. New revision: r",
            { text: "Proposed 1 workflow change disconnected e9", target: { kind: "workflow" } }],
        ["propose ops summaries", "workbench_propose_workflow_changes",
            { changeSet: { ops: [{ op: "updateEdge", id: "e1" }, { op: "metadata", patch: {} }, { op: "renameNode", id: "a", newId: "b" }] } },
            "APPLIED. New revision: r",
            { text: "Proposed 3 workflow changes updated e1, changed metadata, changed a" }],
        ["propose rejected", "workbench_propose_workflow_changes", { changeSet: { ops } },
            "REJECTED (target-missing at op 2): node old missing",
            { text: "Workflow changes rejected: node old missing", variant: "danger", target: { kind: "workflow" } }],
        ["suggest", "workbench_suggest_test", { type: "tool", name: "t" }, "Suggested a test of tool t with inputs {}.",
            { text: "Suggested a test for tool t", target: { kind: "suggestion", itemType: "tool", name: "t" } }],
        ["eval ok", "workbench_evaluate_expression", { expression: "1+1" }, "OK (number): 2",
            { text: "Evaluated 1+1 → 2", variant: "default" }],
        ["eval err", "workbench_evaluate_expression", { expression: "x(" }, "ERROR: bad",
            { text: "Evaluated x( → error: bad", variant: "danger" }],
        ["list events", "workbench_list_stream_events", {}, "a\nb\nc", { text: "Looked at 3 stream events" }],
        ["list events one", "workbench_list_stream_events", {}, "a", { text: "Looked at 1 stream event" }],
        ["list events none", "workbench_list_stream_events", {}, "No stream events found.",
            { text: "Looked at 0 stream events" }],
        ["get event", "workbench_get_stream_event", { eventId: "ev1" }, "{}", { text: "Read stream event ev1" }],
        ["get composition", "workbench_get_composition", {}, "{}", { text: "Read the composition", readOnly: true }],
        ["get item", "workbench_get_item", { type: "action-type", name: "a" }, "{}",
            { text: "Read action type a", readOnly: true }],
        ["validate", "workbench_validate_composition", {}, "x", { text: "Checked the composition", readOnly: true }],
        ["axiom", "mcp__workbench__axiom_list_secrets", {}, "x", { text: "Looked up existing items", readOnly: true }],
        ["isError", "workbench_upsert_item", {}, "boom\nmore", { text: "boom", variant: "danger" }],
    ];
    it.each(cases)("%s", (_label, tool, input, result, expected) => {
        const card = describeToolCall(tool, input, result, tool.includes("upsert") && result.startsWith("boom"));
        expect(card).not.toBeNull();
        expect(card).toMatchObject(expected);
        expect(card!.text).toBe([card!.lead, card!.name, card!.detail].filter(Boolean).join(" "));
    });

    it("returns null for unknown tools and missing results", () => {
        expect(describeToolCall("Bash", {}, "x", false)).toBeNull();
        expect(describeToolCall("workbench_get_composition", {}, undefined, false)).toBeNull();
    });

    it("unwraps content-array results", () => {
        const wrapped = JSON.stringify([{ type: "text", text: "VALID: tool/t (created, revision r)" }]);
        expect(describeToolCall("workbench_upsert_item", {}, wrapped, false)?.text).toBe("Created tool t");
    });
});

describe("groupToolCalls", () => {
    it("groups consecutive read-only calls", () => {
        const r = (id: string): ToolCallRef => ({ id, toolName: "x",
            card: { readOnly: true, text: "", lead: "", variant: "default" } });
        const w: ToolCallRef = { id: "w", toolName: "x",
            card: { readOnly: false, text: "", lead: "", variant: "default" } };
        const n: ToolCallRef = { id: "n", toolName: "x", card: null };
        const groups = groupToolCalls([r("1"), r("2"), w, r("3"), n, n]);
        expect(groups.map(g => g.map(c => c.id))).toEqual([["1", "2"], ["w"], ["3"], ["n"], ["n"]]);
    });
});
