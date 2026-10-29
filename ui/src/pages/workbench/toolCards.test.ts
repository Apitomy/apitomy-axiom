import { describe, expect, it } from "vitest";
import serverJs from "../../../../app/src/main/resources/templates/axiom-workbench-mcp/server.js?raw";
import { describeToolCall, groupToolCalls, workbenchTool, WORKBENCH_TOOLS, type ToolCallRef } from "./toolCards";

describe("WORKBENCH_TOOLS", () => {
    it("matches the tools declared by the workbench MCP server", () => {
        const block: string = serverJs.slice(serverJs.indexOf("const TOOLS = ["));
        const declared: string[] = [...block.matchAll(/^ {8}name: "([a-z_]+)",$/gm)].map(m => m[1]);
        expect(declared.length).toBeGreaterThan(0);
        expect([...WORKBENCH_TOOLS].sort()).toEqual([...declared].sort());
    });
});

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
        ["axiom secrets", "mcp__workbench__axiom_list_secrets", {}, "[{\"name\":\"a\"},{\"name\":\"b\"}]",
            { text: "Listed 2 secrets", readOnly: true }],
        ["axiom secrets none", "workbench_axiom_list_secrets", {}, "No secrets configured.",
            { text: "Listed 0 secrets", readOnly: true }],
        ["axiom tools", "workbench_axiom_list_tools", {}, "[{\"name\":\"a\"}]",
            { text: "Listed 1 existing tool", readOnly: true }],
        ["axiom get tool", "workbench_axiom_get_tool", { name: "t" }, "{}",
            { text: "Read existing tool t", readOnly: true }],
        ["axiom action types", "workbench_axiom_list_action_types", {}, "No action types configured.",
            { text: "Listed 0 existing action types", readOnly: true }],
        ["axiom get action type", "workbench_axiom_get_action_type", { name: "a" }, "{}",
            { text: "Read existing action type a", readOnly: true }],
        ["test run", "mcp__workbench__get_test_run", { runId: "r1" },
            "Test run r1: action-type say hello PASSED (exit 0) in 12ms, by me, started now\nInputs: {}",
            { text: "Read test run r1 (passed)", readOnly: true }],
        ["test run missing", "workbench_get_test_run", { runId: "r2" }, "Test run 'r2' not found (only ...).",
            { text: "Read test run r2" }],
        ["preview", "workbench_preview_subscription", { filterExpression: "x" },
            "3 of 200 recent events match (5 in all events).\n- e1",
            { text: "Previewed subscription filter: 3 of 200 recent events matched", readOnly: true }],
        ["runs", "workbench_list_workflow_runs", {}, "#2 RUNNING v1 s started t\n#1 DONE v1 s started t",
            { text: "Looked at 2 workflow runs", readOnly: true }],
        ["runs unpublished", "workbench_list_workflow_runs", {},
            "The workflow has not been published yet, so it has no runs.", { text: "Looked at 0 workflow runs" }],
        ["run", "workbench_get_workflow_run", { runId: 7 }, "Run #7: t\nsubject: s\nstatus: FAILED\n",
            { text: "Read workflow run #7 (failed)", readOnly: true }],
        ["trace", "workbench_get_trace", { traceId: "tr1" }, "Trace tr1: ERROR - boom\n- a",
            { text: "Read trace tr1 (error)", readOnly: true }],
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

describe("subscription items", () => {
    it("treats subscription as an item type with its own label", () => {
        expect(describeToolCall("workbench_upsert_item", {},
            "VALID: subscription/on-pr (created, revision sha256:abc)", false)).toMatchObject({
            text: "Created subscription on-pr",
            target: { kind: "item", itemType: "subscription", name: "on-pr" },
        });
    });
});
