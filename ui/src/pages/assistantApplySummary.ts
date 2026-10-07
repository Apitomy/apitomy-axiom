import type { AssistantApplyResult } from "../config/api";

/** Item types in apply order, with their result-count prefixes and display names. */
const ITEM_TYPES: { key: string; label: string }[] = [
    { key: "tools", label: "tool" },
    { key: "toolsets", label: "toolset" },
    { key: "actionTypes", label: "action type" },
    { key: "sessionTemplates", label: "session template" },
    { key: "workflowDefinitions", label: "workflow" },
    { key: "eventSources", label: "event source" },
    { key: "subscriptions", label: "subscription" },
];

/**
 * Builds the "applied" summary lines for an assistant apply result: one line per item type and action
 * (created/updated) with a non-zero count, in apply order.
 *
 * @param result the apply result
 * @returns lines such as "4 action type(s) created"
 */
export function applySummaryLines(result: AssistantApplyResult): string[] {
    const counts = result as Record<string, number | undefined>;
    const lines: string[] = [];
    for (const { key, label } of ITEM_TYPES) {
        for (const action of ["Created", "Updated"]) {
            const count = counts[`${key}${action}`] ?? 0;
            if (count > 0) {
                lines.push(`${count} ${label}(s) ${action.toLowerCase()}`);
            }
        }
    }
    return lines;
}
