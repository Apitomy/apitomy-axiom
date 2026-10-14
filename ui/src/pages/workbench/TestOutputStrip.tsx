import { type ReactNode, useState } from "react";
import { Button, ExpandableSection, Label, Spinner } from "@patternfly/react-core";
import {
    AngleDownIcon, AngleRightIcon, AngleUpIcon, CheckCircleIcon, ExclamationCircleIcon, ExclamationTriangleIcon,
    OutlinedLightbulbIcon,
} from "@patternfly/react-icons";
import type { CompositionTestRun } from "../../config/compositions";

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
    if (run.itemType === "action-type" && run.outputs) {
        return `Outputs: ${JSON.stringify(run.outputs)}`;
    }
    return "";
}

function Details({ run }: { run: CompositionTestRun }) {
    const [scriptOpen, setScriptOpen] = useState<boolean>(false);
    return (
        <div className="axiom-test-strip__details">
            <h4>Inputs</h4>
            <pre>{JSON.stringify(run.inputs ?? {}, null, 2)}</pre>
            {run.outputs && <><h4>Outputs</h4><pre>{JSON.stringify(run.outputs, null, 2)}</pre></>}
            {(run.problems ?? []).length > 0 && (
                <><h4>Problems</h4><ul>{run.problems?.map((p, i) => <li key={`${i}-${p}`}>{p}</li>)}</ul></>
            )}
            {run.stdout && <><h4>stdout</h4><pre>{run.stdout}</pre></>}
            {run.stderr && <><h4>stderr</h4><pre>{run.stderr}</pre></>}
            {run.resolvedScript && (
                <ExpandableSection toggleText="Resolved script" isExpanded={scriptOpen}
                    onToggle={(_e, open) => setScriptOpen(open)}>
                    <pre>{run.resolvedScript}</pre>
                </ExpandableSection>
            )}
        </div>
    );
}

/**
 * Collapsible list of the composition's recent test runs, by the user and the AI.
 */
export function TestOutputStrip({ runs, collapsed, onToggle, onOpenItem, actions, onRunSuggestion }: TestOutputStripProps) {
    const [open, setOpen] = useState<Set<string>>(new Set<string>());
    const toggle = (id: string) => setOpen((prev) => {
        const next = new Set<string>(prev);
        if (next.has(id)) {
            next.delete(id);
        } else {
            next.add(id);
        }
        return next;
    });
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
                                <div className="axiom-test-strip__row">
                                    <Button variant="plain" size="sm" aria-label={open.has(run.id) ? "Hide details" : "Show details"}
                                        aria-expanded={open.has(run.id)} onClick={() => toggle(run.id)}
                                        icon={open.has(run.id) ? <AngleDownIcon /> : <AngleRightIcon />} />
                                    <StatusIcon status={run.status} />
                                    <Button variant="link" isInline onClick={() => onOpenItem(run.itemId)}>{run.itemName}</Button>
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
                                            onClick={() => onRunSuggestion?.(run)}>Run…</Button>
                                    )}
                                </div>
                                {open.has(run.id) && <Details run={run} />}
                            </li>
                        ))}
                    </ul>
                </div>
            )}
        </section>
    );
}
