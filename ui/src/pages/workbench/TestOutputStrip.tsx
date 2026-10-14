import { type KeyboardEvent, type ReactNode, useState } from "react";
import { Button, Label, Spinner } from "@patternfly/react-core";
import {
    AngleDownIcon, AngleUpIcon, CheckCircleIcon, ExclamationCircleIcon, ExclamationTriangleIcon,
    OutlinedLightbulbIcon,
} from "@patternfly/react-icons";
import type { CompositionTestRun } from "../../config/compositions";
import { TestRunDetailModal } from "./TestRunDetailModal";

/** Props for {@link TestOutputStrip}. */
export interface TestOutputStripProps {
    /** Runs, newest first. */
    runs: CompositionTestRun[];
    collapsed: boolean;
    onToggle(): void;
    onOpenItem(itemId: number): void;
    /** Controls shown at the right of the header (e.g. the Test… button). */
    actions?: ReactNode;
    /** Called when the user chooses to run an AI-suggested test. */
    onRunSuggestion?(run: CompositionTestRun): void;
}

function StatusIcon({ status }: { status: CompositionTestRun["status"] }) {
    switch (status) {
        case "running":
            return <Spinner size="sm" aria-label="running" />;
        case "suggested":
            return <OutlinedLightbulbIcon aria-label="suggested" color="var(--pf-t--global--icon--color--status--info--default)" />;
        case "passed":
            return <CheckCircleIcon aria-label="passed" color="var(--pf-t--global--icon--color--status--success--default)" />;
        case "failed":
            return <ExclamationCircleIcon aria-label="failed" color="var(--pf-t--global--icon--color--status--danger--default)" />;
        default:
            return <ExclamationTriangleIcon aria-label="error" color="var(--pf-t--global--icon--color--status--warning--default)" />;
    }
}

/**
 * One-line summary of a run.
 * @param run the run
 * @returns the summary text
 */
export function runSummary(run: CompositionTestRun): string {
    if (run.status === "running") {
        return "Running…";
    }
    if (run.status === "suggested") {
        return `Inputs: ${JSON.stringify(run.inputs ?? {})}`;
    }
    if (run.message) {
        return run.message;
    }
    if (run.problems && run.problems.length > 0) {
        return run.problems[0];
    }
    // A failed run without problems: show the last meaningful stderr line (skipping stack frames).
    if (run.status === "failed") {
        const lastErrorLine: string | undefined = (run.stderr ?? "").split("\n").map((l) => l.trim())
            .filter((l) => l !== "" && !l.startsWith("at ") && l !== "}" && l !== "{").pop();
        if (lastErrorLine) {
            return lastErrorLine;
        }
    }
    if (run.itemType === "action-type" && run.outputs) {
        return `Outputs: ${JSON.stringify(run.outputs)}`;
    }
    // A tool has no declared outputs: show the last line it printed.
    if (run.itemType === "tool") {
        return (run.stdout ?? "").split("\n").map((l) => l.trim()).filter((l) => l !== "").pop() ?? "";
    }
    return "";
}

/**
 * Collapsible list of the composition's recent test runs, by the user and the AI.
 */
export function TestOutputStrip({ runs, collapsed, onToggle, onOpenItem, actions, onRunSuggestion }: TestOutputStripProps) {
    const [selectedId, setSelectedId] = useState<string | null>(null);
    // Show the latest version of the selected run (it may still be running when the dialog opens).
    const selected: CompositionTestRun | undefined = runs.find((r) => r.id === selectedId);
    const onRowKey = (e: KeyboardEvent, id: string) => {
        if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            setSelectedId(id);
        }
    };
    return (
        <section className="axiom-test-strip" aria-label="Test output">
            <div className="axiom-test-strip__header">
                <Button variant="plain" size="sm" onClick={onToggle}
                    aria-label={collapsed ? "Expand test output" : "Collapse test output"}
                    icon={collapsed ? <AngleUpIcon /> : <AngleDownIcon />} />
                <span className="axiom-workbench__pane-title">Test output</span>
                <Label isCompact>{runs.length}</Label>
                <span className="axiom-workbench__spacer" />
                {actions}
            </div>
            {!collapsed && (
                <div className="axiom-test-strip__body">
                    {runs.length === 0 && (
                        <p className="axiom-text-subtle axiom-test-strip__empty">
                            No test runs yet. Use Test… on an action type or tool, or ask the AI to test one.
                        </p>
                    )}
                    <ul className="axiom-test-strip__list">
                        {runs.map((run) => (
                            <li key={run.id}>
                                <div className="axiom-test-strip__row" role="button" tabIndex={0}
                                    aria-label={`Show details of the ${run.itemName} test run`}
                                    onClick={() => setSelectedId(run.id)} onKeyDown={(e) => onRowKey(e, run.id)}>
                                    <StatusIcon status={run.status} />
                                    <Button variant="link" isInline onClick={(e) => {
                                        e.stopPropagation();
                                        onOpenItem(run.itemId);
                                    }}>{run.itemName}</Button>
                                    {run.status === "suggested"
                                        ? <Label isCompact color="purple">Suggested by AI</Label>
                                        : run.author === "ai" && <Label isCompact color="purple">AI</Label>}
                                    <span className="axiom-text-subtle">
                                        {new Date(run.startedAt).toLocaleTimeString()}
                                        {run.durationMs !== undefined && run.status !== "running" && run.status !== "suggested" ? ` · ${run.durationMs} ms` : ""}
                                        {run.exitCode !== undefined && run.status !== "suggested" ? ` · exit ${run.exitCode}` : ""}
                                    </span>
                                    <span className="axiom-test-strip__summary">{runSummary(run)}</span>
                                    {run.status === "suggested" && (
                                        <Button variant="secondary" size="sm"
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                onRunSuggestion?.(run);
                                            }}>Run…</Button>
                                    )}
                                </div>
                            </li>
                        ))}
                    </ul>
                </div>
            )}
            {selected && (
                <TestRunDetailModal run={selected} onClose={() => setSelectedId(null)} onOpenItem={onOpenItem}
                    onRunSuggestion={onRunSuggestion} />
            )}
        </section>
    );
}
