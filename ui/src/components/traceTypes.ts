import type { LabelProps } from "@patternfly/react-core";

/** Display labels for trace types; unknown types are shown as-is. */
export const TRACE_TYPE_LABELS: Record<string, string> = {
    manager: "Manager",
    "manager-dry-run": "Manager dry run",
    workflow: "Workflow",
    "scheduled-job-execution": "Scheduled job",
    "report-generation": "Report generation",
    "user-action": "User action",
};

/** Returns the display label for a trace type. */
export function traceTypeLabel(traceType: string): string {
    return TRACE_TYPE_LABELS[traceType] || traceType;
}

/** Label colours for trace types; types not listed use the default colour. */
export const TRACE_TYPE_COLORS: Record<string, LabelProps["color"]> = {
    "manager-dry-run": "purple",
};

/** Returns the label colour for a trace type, or undefined for the default colour. */
export function traceTypeColor(traceType: string): LabelProps["color"] | undefined {
    return TRACE_TYPE_COLORS[traceType];
}
