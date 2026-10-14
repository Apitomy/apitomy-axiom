/** The run fields the analysis prompt uses (from a run summary or a full run). */
export interface RunAnalysisInput {
    id: number;
    status: string;
    currentNodeName?: string;
    /** The failure reason, or a failed task's output; only its first non-empty line is used. */
    reason?: string;
}

/**
 * Builds the chat prefill that asks the AI to analyze a workflow run.
 * @param run the run
 * @param workflowName the name of the workflow
 * @returns the prompt text
 */
export function runAnalysisPrefill(run: RunAnalysisInput, workflowName: string): string {
    const node: string = run.currentNodeName ? ` at node \`${run.currentNodeName}\`` : "";
    const line: string | undefined = (run.reason ?? "").split("\n").map((l) => l.trim()).find((l) => l.length > 0);
    const reason: string = line ? `: ${line}` : "";
    return `Analyze run #${run.id} of workflow \`${workflowName}\` (status: ${run.status}${node})${reason}.`
        + " Find the cause and fix the workflow or its components.";
}
