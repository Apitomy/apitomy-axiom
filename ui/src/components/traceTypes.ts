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
