/** Workbench MCP tool names (without the `mcp__workbench__` / `workbench_` prefix). */
export type WorkbenchTool = "get_composition" | "get_item" | "upsert_item" | "remove_item"
    | "propose_workflow_changes" | "validate_composition" | "suggest_test" | "list_stream_events"
    | "get_stream_event" | "evaluate_expression" | "get_test_run" | "preview_subscription"
    | "list_workflow_runs" | "get_workflow_run" | "get_trace" | "axiom_list_action_types"
    | "axiom_get_action_type" | "axiom_list_tools" | "axiom_get_tool" | "axiom_list_secrets";

/** Every workbench MCP tool the chat knows how to label (must match the tools declared by server.js). */
export const WORKBENCH_TOOLS: readonly WorkbenchTool[] = [
    "get_composition", "get_item", "upsert_item", "remove_item", "propose_workflow_changes",
    "validate_composition", "suggest_test", "list_stream_events", "get_stream_event", "evaluate_expression",
    "get_test_run", "preview_subscription", "list_workflow_runs", "get_workflow_run", "get_trace",
    "axiom_list_action_types", "axiom_get_action_type", "axiom_list_tools", "axiom_get_tool", "axiom_list_secrets",
];

const TOOLS: ReadonlySet<string> = new Set<string>(WORKBENCH_TOOLS);

/** Tools that only read state; their cards are grouped together in the chat. */
const READ_ONLY_TOOLS: ReadonlySet<WorkbenchTool> = new Set<WorkbenchTool>([
    "get_composition", "get_item", "validate_composition", "get_test_run", "preview_subscription",
    "list_workflow_runs", "get_workflow_run", "get_trace", "axiom_list_action_types", "axiom_get_action_type",
    "axiom_list_tools", "axiom_get_tool", "axiom_list_secrets",
]);

const PREFIXES: string[] = ["mcp__workbench__", "workbench_"];

/** Strips `mcp__workbench__` / `workbench_`; null when the name is not a workbench tool. */
export function workbenchTool(toolName: string): WorkbenchTool | null {
    for (const prefix of PREFIXES) {
        if (toolName.startsWith(prefix)) {
            const bare: string = toolName.slice(prefix.length);
            return TOOLS.has(bare) ? (bare as WorkbenchTool) : null;
        }
    }
    return null;
}

/** Where clicking a card navigates. */
export type CardTarget =
    | { kind: "item"; itemType: "action-type" | "tool" | "workflow" | "subscription"; name: string }
    | { kind: "workflow"; nodeId?: string }
    | { kind: "suggestion"; itemType: "action-type" | "tool"; name: string };

/** A compact, human-readable description of one workbench tool call. */
export interface ToolCard {
    readOnly: boolean;
    /** Plain text of the card (exact texts in the plan's Global Constraints); used by tests and tooltips. */
    text: string;
    /** The leading verb phrase, e.g. "Updated action type"; the component renders it before `name`. */
    lead: string;
    /** Item, node or expression shown in monospace after `lead`, if any. */
    name?: string;
    /** Text after the name, e.g. op summaries or "→ value". */
    detail?: string;
    status?: "VALID" | "VALID with warnings" | "INVALID";
    variant: "default" | "danger";
    target?: CardTarget;
}

type ItemType = "action-type" | "tool" | "workflow" | "subscription";

function kindLabel(type: string): string {
    return type === "action-type" ? "action type" : type;
}

function isItemType(type: string): type is ItemType {
    return type === "action-type" || type === "tool" || type === "workflow" || type === "subscription";
}

function str(value: unknown): string {
    return typeof value === "string" ? value : value === undefined || value === null ? "" : String(value);
}

function plural(n: number, word: string): string {
    return `${n} ${word}${n === 1 ? "" : "s"}`;
}

/** Unwraps results delivered as JSON content arrays (`[{type:"text",text}]`) or JSON-encoded strings. */
export function unwrapResult(result: string): string {
    const trimmed: string = result.trim();
    if (!(trimmed.startsWith("[") || trimmed.startsWith("\""))) return result;
    try {
        const parsed: unknown = JSON.parse(trimmed);
        if (typeof parsed === "string") return parsed;
        if (Array.isArray(parsed) && parsed.length > 0
            && parsed.every(p => p && typeof p === "object" && (p as { type?: unknown }).type === "text")) {
            return parsed.map(p => str((p as { text?: unknown }).text)).join("\n");
        }
    } catch {
        // not JSON: use as-is
    }
    return result;
}

function card(readOnly: boolean, lead: string, name?: string, detail?: string,
              extra: Partial<ToolCard> = {}): ToolCard {
    const text: string = [lead, name, detail].filter(Boolean).join(" ");
    return { readOnly, text, lead, name: name || undefined, detail: detail || undefined, variant: "default", ...extra };
}

/** One change-set op. */
export type Op = Record<string, unknown>;

/** The ops of a propose_workflow_changes input (whose changeSet may be a JSON string). */
export function opsOf(input: Record<string, unknown> | undefined): Op[] {
    let changeSet: unknown = input?.changeSet;
    if (typeof changeSet === "string") {
        try {
            changeSet = JSON.parse(changeSet);
        } catch {
            return [];
        }
    }
    const ops: unknown = (changeSet as { ops?: unknown } | undefined)?.ops;
    return Array.isArray(ops) ? (ops.filter(o => o && typeof o === "object") as Op[]) : [];
}

function nodeIdOf(op: Op): string | undefined {
    const node: Op | undefined = op.node as Op | undefined;
    const id: unknown = node?.id ?? op.nodeId ?? op.id;
    return typeof id === "string" ? id : undefined;
}

/** One-line summary of a change-set op, e.g. "added Log" or "connected a → b". */
export function summarizeOp(op: Op): string {
    const node: Op = (op.node as Op | undefined) ?? {};
    const edge: Op = (op.edge as Op | undefined) ?? {};
    switch (op.op) {
        case "addNode": return `added ${str(node.name) || str(node.id)}`;
        case "removeNode": return `removed ${str(op.id)}`;
        case "addEdge": return `connected ${str(edge.source)} → ${str(edge.target)}`;
        case "removeEdge": return `disconnected ${str(op.id)}`;
        case "updateNode":
        case "updateEdge": return `updated ${str(op.id)}`;
        default: return `changed ${str(op.id) || str(op.op)}`;
    }
}

const UPSERT_RE: RegExp = /^(VALID with warnings|VALID|INVALID): (\S+?)\/(.+?) \((?:(created|updated), )?/;

/** Describes a finished workbench tool call; null for unknown tools or calls without a result yet. */
export function describeToolCall(toolName: string, input: Record<string, unknown> | undefined,
                                 result: string | undefined, isError: boolean | undefined): ToolCard | null {
    const tool: WorkbenchTool | null = workbenchTool(toolName);
    if (tool === null || result === undefined) return null;
    const text: string = unwrapResult(result);
    const firstLine: string = text.split("\n")[0].trim();
    const readOnly: boolean = READ_ONLY_TOOLS.has(tool);
    if (isError === true) {
        return card(readOnly, firstLine || "Error", undefined, undefined, { variant: "danger" });
    }
    const type: string = str(input?.type);
    const name: string = str(input?.name);
    switch (tool) {
        case "upsert_item": {
            const m: RegExpExecArray | null = UPSERT_RE.exec(firstLine);
            if (!m) return card(false, "Updated item", undefined, undefined);
            const status = m[1] as ToolCard["status"];
            const itemType: string = m[2];
            const target: CardTarget | undefined = isItemType(itemType)
                ? { kind: "item", itemType, name: m[3] } : undefined;
            return card(false, `${m[4] === "created" ? "Created" : "Updated"} ${kindLabel(itemType)}`, m[3],
                undefined, { status, target });
        }
        case "remove_item":
            return card(false, `Removed ${kindLabel(type)}`, name);
        case "propose_workflow_changes": {
            if (firstLine.startsWith("REJECTED")) {
                const idx: number = firstLine.indexOf(": ");
                const reason: string = idx >= 0 ? firstLine.slice(idx + 2) : firstLine;
                return card(false, "Workflow changes rejected:", undefined, reason,
                    { variant: "danger", target: { kind: "workflow" } });
            }
            const ops: Op[] = opsOf(input);
            const summaries: string[] = ops.slice(0, 3).map(summarizeOp);
            const more: string = ops.length > 3 ? ` and ${ops.length - 3} more` : "";
            const detail: string = summaries.length > 0 ? summaries.join(", ") + more : "";
            return card(false, `Proposed ${plural(ops.length, "workflow change")}`, undefined, detail,
                { target: { kind: "workflow", nodeId: ops.length > 0 ? nodeIdOf(ops[0]) : undefined } });
        }
        case "suggest_test": {
            const target: CardTarget | undefined = type === "action-type" || type === "tool"
                ? { kind: "suggestion", itemType: type, name } : undefined;
            return card(false, `Suggested a test for ${kindLabel(type)}`, name, undefined, { target });
        }
        case "evaluate_expression": {
            const expression: string = str(input?.expression);
            const ok: RegExpExecArray | null = /^OK \([^)]*\): ([\s\S]*)$/.exec(text.trim());
            if (ok) return card(false, "Evaluated", expression, `→ ${ok[1]}`);
            const err: string = text.trim().startsWith("ERROR: ") ? text.trim().slice(7) : text.trim();
            return card(false, "Evaluated", expression, `→ error: ${err}`, { variant: "danger" });
        }
        case "list_stream_events": {
            const n: number = firstLine === "No stream events found." ? 0
                : text.split("\n").filter(l => l.trim() !== "").length;
            return card(false, `Looked at ${plural(n, "stream event")}`);
        }
        case "get_stream_event":
            return card(false, "Read stream event", str(input?.eventId));
        case "get_composition":
            return card(true, "Read the composition");
        case "get_item":
            return card(true, `Read ${kindLabel(type)}`, name);
        case "validate_composition":
            return card(true, "Checked the composition");
        case "get_test_run": {
            const runId: string = str(input?.runId);
            const m: RegExpExecArray | null = TEST_RUN_RE.exec(firstLine);
            return card(true, "Read test run", runId, m ? `(${m[1].toLowerCase()})` : undefined);
        }
        case "preview_subscription": {
            const m: RegExpExecArray | null = /^(\d+) of (\d+) recent events match/.exec(firstLine);
            return card(true, "Previewed subscription filter:", undefined,
                m ? `${m[1]} of ${plural(Number(m[2]), "recent event")} matched` : undefined);
        }
        case "list_workflow_runs": {
            const n: number = firstLine.startsWith("#") ? nonEmptyLines(text) : 0;
            return card(true, `Looked at ${plural(n, "workflow run")}`);
        }
        case "get_workflow_run": {
            const status: RegExpExecArray | null = /^status: (\S+)$/m.exec(text);
            return card(true, "Read workflow run", `#${str(input?.runId)}`,
                status ? `(${status[1].toLowerCase()})` : undefined);
        }
        case "get_trace": {
            const m: RegExpExecArray | null = /^Trace \S+: (\S+)/.exec(firstLine);
            return card(true, "Read trace", str(input?.traceId), m ? `(${m[1].toLowerCase()})` : undefined);
        }
        case "axiom_list_tools":
            return card(true, `Listed ${plural(jsonArrayLength(text), "existing tool")}`);
        case "axiom_get_tool":
            return card(true, "Read existing tool", name);
        case "axiom_list_action_types":
            return card(true, `Listed ${plural(jsonArrayLength(text), "existing action type")}`);
        case "axiom_get_action_type":
            return card(true, "Read existing action type", name);
        case "axiom_list_secrets":
            return card(true, `Listed ${plural(jsonArrayLength(text), "secret")}`);
        default:
            return card(true, "Looked up existing items");
    }
}

/** Matches the status of a get_test_run header: "Test run {id}: {type} {name} {STATUS}[ (exit n)][ in nms], by". */
const TEST_RUN_RE: RegExp = /^Test run \S+: .+? (\S+?)(?: \(exit -?\d+\))?(?: in \d+ms)?, by /;

function nonEmptyLines(text: string): number {
    return text.split("\n").filter(l => l.trim() !== "").length;
}

/** Length of a JSON array result; 0 for non-array results such as "No tools configured.". */
function jsonArrayLength(text: string): number {
    try {
        const parsed: unknown = JSON.parse(text);
        return Array.isArray(parsed) ? parsed.length : 0;
    } catch {
        return 0;
    }
}

/** A tool call with its (possibly null) card, as input to {@link groupToolCalls}. */
export interface ToolCallRef { id: string; toolName: string; card: ToolCard | null }

/** Groups consecutive read-only cards; non-read-only cards and null cards stay single. */
export function groupToolCalls(calls: ToolCallRef[]): ToolCallRef[][] {
    const groups: ToolCallRef[][] = [];
    let current: ToolCallRef[] | null = null;
    for (const call of calls) {
        if (call.card?.readOnly) {
            if (current === null) {
                current = [];
                groups.push(current);
            }
            current.push(call);
        } else {
            current = null;
            groups.push([call]);
        }
    }
    return groups;
}
