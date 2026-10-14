import type { KeyboardEvent } from "react";
import { Button, Label } from "@patternfly/react-core";
import type { WorkflowRunSummary } from "../../config/api";
import { STATUS_COLORS } from "../../components/workflowRun";

/** Props for {@link RunsList}. */
export interface RunsListProps {
    /** Runs, newest first. */
    runs: WorkflowRunSummary[];
    /** Whether the workflow has a published definition. */
    published: boolean;
    /** Load error, if any. */
    error?: string | null;
    onOpenRun(runId: number): void;
    onAskAi(run: WorkflowRunSummary): void;
}

/**
 * List of the runs of the composition's published workflow, shown in the Runs tab of the bottom panel.
 */
export function RunsList({ runs, published, error, onOpenRun, onAskAi }: RunsListProps) {
    if (!published) {
        return <p className="axiom-text-subtle axiom-test-strip__empty">Publish the workflow to see its runs here.</p>;
    }
    const onRowKey = (e: KeyboardEvent, run: WorkflowRunSummary) => {
        if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            onOpenRun(run.runId);
        }
    };
    return (
        <>
            {error && <p className="axiom-test-strip__empty pf-v6-u-danger-color-100">Could not load runs: {error}</p>}
            {!error && runs.length === 0 && (
                <p className="axiom-text-subtle axiom-test-strip__empty">No runs of this workflow yet.</p>
            )}
            <ul className="axiom-test-strip__list">
                {runs.map((run) => (
                    <li key={run.runId}>
                        <div className="axiom-test-strip__row" role="button" tabIndex={0}
                            aria-label={`Show run #${run.runId}`}
                            onClick={() => onOpenRun(run.runId)} onKeyDown={(e) => onRowKey(e, run)}>
                            <Label isCompact color={STATUS_COLORS[run.status] || "grey"}>{run.status}</Label>
                            <span>#{run.runId}</span>
                            {/* The title takes the remaining width and is truncated, so the row never scrolls sideways. */}
                            <span className="axiom-test-strip__summary" title={run.title}>{run.title}</span>
                            <span className="axiom-text-subtle">v{run.definitionVersion}</span>
                            <span className="axiom-text-subtle">
                                {new Date(run.startedOn).toLocaleString()}
                                {(run.status === "failed" || run.status === "waiting") && run.currentNodeName
                                    ? ` · ${run.currentNodeName}` : ""}
                            </span>
                            {run.status === "failed" && (
                                <Button variant="link" isInline size="sm" onClick={(e) => {
                                    e.stopPropagation();
                                    onAskAi(run);
                                }}>Ask AI to analyze</Button>
                            )}
                        </div>
                    </li>
                ))}
            </ul>
        </>
    );
}
