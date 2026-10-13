import type { ActionTypeDescriptor, ActionTypeField, Workflow } from "@apitomy/flow-ui";
import type { Composition, CompositionItem, ConfigProblem, UndoResult } from "../../config/compositions";

/** Validation status of a composition item. */
export type ItemStatus = "valid" | "warnings" | "errors" | "unknown";

const TOOL_PREFIX: string = "mcp__axiom-tools__";

/**
 * Derives an item's status from its validation problems.
 * @param problems the problems, or undefined when not yet validated
 * @returns the status
 */
export function itemStatus(problems: ConfigProblem[] | undefined): ItemStatus {
    if (!problems) {
        return "unknown";
    }
    if (problems.some((p: ConfigProblem) => p.severity === "error")) {
        return "errors";
    }
    return problems.length > 0 ? "warnings" : "valid";
}

/**
 * Whether the item was last touched by the AI in the given turn.
 * @param item the item
 * @param turnId the turn id
 * @returns true when the item's last turn is the given turn
 */
export function isAiTouchedInTurn(item: CompositionItem, turnId: string | undefined): boolean {
    return !!turnId && item.lastTurnId === turnId;
}

/**
 * Whether the user must not edit the item because the running AI turn touched it.
 * The workflow is never locked.
 * @param item the item
 * @param composition the composition
 * @returns true when locked
 */
export function isLockedForUser(item: CompositionItem, composition: Composition): boolean {
    return composition.turnState === "ai-working"
        && item.type !== "workflow"
        && !!composition.currentTurnId
        && item.lastTurnId === composition.currentTurnId;
}

/**
 * Returns the non-SDK action types referenced by a workflow's action nodes, deduplicated, in order.
 * @param workflow the workflow
 * @returns action type names
 */
export function referencedActionTypes(workflow: Workflow): string[] {
    const result: string[] = [];
    for (const node of workflow.nodes ?? []) {
        if (node.type !== "action") {
            continue;
        }
        const config: Record<string, unknown> = (node as { config?: Record<string, unknown> }).config ?? {};
        const actionType: unknown = config.actionType;
        if (typeof actionType === "string" && actionType && !actionType.startsWith("sdk:")
            && !result.includes(actionType)) {
            result.push(actionType);
        }
    }
    return result;
}

/**
 * Returns the Axiom tool names referenced by an action type's `allowedTools`.
 * @param actionType the action type content
 * @returns tool names, deduplicated, in order
 */
export function referencedTools(actionType: Record<string, unknown>): string[] {
    const raw: unknown = actionType.allowedTools;
    let entries: string[] = [];
    if (typeof raw === "string") {
        entries = raw.split(/[\s,]+/);
    } else if (Array.isArray(raw)) {
        entries = raw.filter((e: unknown): e is string => typeof e === "string").map((e: string) => e.trim());
    }
    const result: string[] = [];
    for (const entry of entries) {
        if (entry.startsWith(TOOL_PREFIX)) {
            const name: string = entry.substring(TOOL_PREFIX.length);
            if (name && !result.includes(name)) {
                result.push(name);
            }
        }
    }
    return result;
}

/**
 * Expands a publish selection with the changed draft dependencies of the chosen items, transitively.
 * @param items the composition items
 * @param contents item contents keyed by item id
 * @param chosen the chosen item ids
 * @returns the expanded ids, sorted ascending
 */
export function publishSelection(items: CompositionItem[],
                                 contents: Record<number, Record<string, unknown>>,
                                 chosen: number[]): number[] {
    const selected: Set<number> = new Set<number>(chosen);
    const queue: number[] = [...chosen];
    const findChanged = (type: CompositionItem["type"], name: string): CompositionItem | undefined =>
        items.find((i: CompositionItem) => i.type === type && i.name === name && i.changed);
    while (queue.length > 0) {
        const id: number = queue.shift() as number;
        const item: CompositionItem | undefined = items.find((i: CompositionItem) => i.id === id);
        const content: Record<string, unknown> | undefined = contents[id] ?? item?.content;
        if (!item || !content) {
            continue;
        }
        let deps: CompositionItem[] = [];
        if (item.type === "workflow") {
            deps = referencedActionTypes({ id: "", name: "", edges: [], nodes: [], ...content } as Workflow)
                .map((n: string) => findChanged("action-type", n))
                .filter((d: CompositionItem | undefined): d is CompositionItem => !!d);
        } else if (item.type === "action-type") {
            deps = referencedTools(content)
                .map((n: string) => findChanged("tool", n))
                .filter((d: CompositionItem | undefined): d is CompositionItem => !!d);
        }
        for (const dep of deps) {
            if (!selected.has(dep.id)) {
                selected.add(dep.id);
                queue.push(dep.id);
            }
        }
    }
    return [...selected].sort((a: number, b: number) => a - b);
}

/**
 * The default publish choice: changed items that have no errors.
 * @param items the composition items
 * @param statuses item statuses keyed by id
 * @returns item ids
 */
export function defaultPublishChoice(items: CompositionItem[], statuses: Record<number, ItemStatus>): number[] {
    return items
        .filter((i: CompositionItem) => i.changed && statuses[i.id] !== "errors")
        .map((i: CompositionItem) => i.id);
}

/** The UI plan for applying an undo result. */
export interface UndoPlan {
    replaceWorkflow: Workflow | null;
    message: string;
}

/**
 * Decides whether the undo should replace the workflow in the editor.
 * @param result the server undo result
 * @param lastWorkflowSaveTurnId the turn id of the latest workflow save
 * @param turnId the undone turn id
 * @returns the plan
 */
export function undoPlan(result: UndoResult, lastWorkflowSaveTurnId: string | undefined, turnId: string): UndoPlan {
    if (result.workflowRestorable && lastWorkflowSaveTurnId === turnId && result.workflowBefore) {
        return { replaceWorkflow: result.workflowBefore as unknown as Workflow, message: "Undid the AI turn." };
    }
    return {
        replaceWorkflow: null,
        message: "Undid the AI turn. The workflow was left alone because it changed after that turn.",
    };
}

/**
 * Merges draft action types into the database catalog. Drafts override by value; new drafts are appended.
 * @param database the database catalog
 * @param drafts draft action types
 * @returns the merged catalog
 */
export function mergeCatalog(database: ActionTypeDescriptor[],
                             drafts: { name: string; content: Record<string, unknown> }[]): ActionTypeDescriptor[] {
    const toDescriptor = (d: { name: string; content: Record<string, unknown> }): ActionTypeDescriptor => {
        const descriptor: ActionTypeDescriptor = { value: d.name, label: d.name };
        if (typeof d.content.description === "string") {
            descriptor.description = d.content.description;
        }
        if (Array.isArray(d.content.inputs)) {
            descriptor.inputs = d.content.inputs as ActionTypeField[];
        }
        if (Array.isArray(d.content.outputs)) {
            descriptor.outputs = d.content.outputs as ActionTypeField[];
        }
        return descriptor;
    };
    const byName: Map<string, ActionTypeDescriptor> = new Map(
        drafts.map((d: { name: string; content: Record<string, unknown> }) => [d.name, toDescriptor(d)]));
    const merged: ActionTypeDescriptor[] = database.map((d: ActionTypeDescriptor) => byName.get(d.value) ?? d);
    const existing: Set<string> = new Set(database.map((d: ActionTypeDescriptor) => d.value));
    byName.forEach((d: ActionTypeDescriptor, name: string) => {
        if (!existing.has(name)) {
            merged.push(d);
        }
    });
    return merged;
}

/**
 * Describes where a composition item is still used by the other drafts: the workflow's action nodes for an
 * action type, and the action types' allowed tools for a tool.
 * @param item the item about to be removed
 * @param items the composition items
 * @param contents item contents keyed by item id
 * @returns one human-readable entry per user, empty when the item is unused (or is the workflow)
 */
export function itemUsages(item: CompositionItem, items: CompositionItem[],
                           contents: Record<number, Record<string, unknown>>): string[] {
    const result: string[] = [];
    if (item.type === "action-type") {
        items.filter((i: CompositionItem) => i.type === "workflow").forEach((wf: CompositionItem) => {
            const workflow = contents[wf.id] as unknown as Workflow | undefined;
            if (!workflow) {
                return;
            }
            const count: number = (workflow.nodes ?? []).filter((n) => n.type === "action"
                && (n as { config?: Record<string, unknown> }).config?.actionType === item.name).length;
            if (count > 0) {
                result.push(`Workflow ${wf.name} (${count} node${count === 1 ? "" : "s"})`);
            }
        });
    } else if (item.type === "tool") {
        items.filter((i: CompositionItem) => i.type === "action-type").forEach((at: CompositionItem) => {
            const content: Record<string, unknown> | undefined = contents[at.id];
            if (content && referencedTools(content).includes(item.name)) {
                result.push(`Action type ${at.name}`);
            }
        });
    }
    return result;
}
