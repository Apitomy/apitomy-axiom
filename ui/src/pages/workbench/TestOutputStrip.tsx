import { type KeyboardEvent, type ReactNode, useState } from "react";
import { Button, Label, Spinner, Tab, Tabs, TabTitleText } from "@patternfly/react-core";
import {
    AngleDownIcon, AngleUpIcon, CheckCircleIcon, ExclamationCircleIcon, ExclamationTriangleIcon,
    OutlinedLightbulbIcon,
} from "@patternfly/react-icons";
import type { CompositionTestRun } from "../../config/compositions";
import { TestRunDetailModal } from "./TestRunDetailModal";
import type { BottomPanelTab } from "./workbenchPrefs";

/** The Tests/Runs tabs of the bottom panel; when given, the strip shows them instead of its title. */
export interface BottomPanelTabs {
    active: BottomPanelTab;
    onSelect(tab: BottomPanelTab): void;
    runsCount: number;
    /** Body of the Runs tab. */
    runsContent: ReactNode;
    /** Header controls of the Runs tab. */
    runsActions?: ReactNode;
}

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
    /** Called when the user chooses to share a finished run with the AI (prefills the chat). */
    onSendToAi?(run: CompositionTestRun): void;
    /** Disables Send to AI (e.g. while the AI is working). */
    isSendToAiDisabled?: boolean;
    /** Id of the run whose details dialog is open; when given (with `onSelectRun`) the selection is controlled. */
    selectedRunId?: string | null;
    /** Called when the details dialog of a run opens (id) or closes (null). */
    onSelectRun?(runId: string | null): void;
    /** Shows the Tests and Runs tabs; the props above then describe the Tests tab. */
    bottomTabs?: BottomPanelTabs;
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
 * Whether a run has finished, so its results can be shared with the AI.
 * @param run the run
 * @returns true for passed, failed and error runs
 */
export function isFinishedRun(run: CompositionTestRun): boolean {
    return run.status !== "running" && run.status !== "suggested";
}

/**
 * Chat prefill that refers the AI to a finished test run; the AI reads its details with get_test_run.
 * @param run the run
 * @returns the prefill text
 */
export function sendTestRunToAiPrefill(run: CompositionTestRun): string {
    const kind: string = run.itemType === "tool" ? "tool" : "action type";
    return `Test run \`${run.id}\` of ${kind} \`${run.itemName}\` (status: ${run.status}): `;
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
export function TestOutputStrip(props: TestOutputStripProps) {
    const { runs, collapsed, onToggle, onOpenItem, actions, onRunSuggestion, onSendToAi, isSendToAiDisabled,
        bottomTabs } = props;
    const runsTab: boolean = bottomTabs?.active === "runs";
    const [ownSelectedId, setOwnSelectedId] = useState<string | null>(null);
    const controlled: boolean = props.selectedRunId !== undefined && props.onSelectRun !== undefined;
    const selectedId: string | null = controlled ? props.selectedRunId ?? null : ownSelectedId;
    const setSelectedId = (id: string | null) => (controlled ? props.onSelectRun?.(id) : setOwnSelectedId(id));
    // Show the latest version of the selected run (it may still be running when the dialog opens).
    const selected: CompositionTestRun | undefined = runs.find((r) => r.id === selectedId);
    /** A suggested run opens the Test dialog (Run…); any other run opens its details. */
    const activate = (run: CompositionTestRun) => {
        if (run.status === "suggested" && onRunSuggestion) {
            onRunSuggestion(run);
        } else {
            setSelectedId(run.id);
        }
    };
    const onRowKey = (e: KeyboardEvent, run: CompositionTestRun) => {
        if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            activate(run);
        }
    };
    return (
        <section className="axiom-test-strip" aria-label="Test output">
            <div className="axiom-test-strip__header">
                <Button variant="plain" size="sm" onClick={onToggle}
                    aria-label={`${collapsed ? "Expand" : "Collapse"} ${bottomTabs ? "bottom panel" : "test output"}`}
                    icon={collapsed ? <AngleUpIcon /> : <AngleDownIcon />} />
                {bottomTabs ? (
                    <Tabs isSubtab activeKey={bottomTabs.active} aria-label="Bottom panel"
                        onSelect={(_e, key) => bottomTabs.onSelect(key === "runs" ? "runs" : "tests")}>
                        <Tab eventKey="tests" title={<TabTitleText>Tests <Label isCompact>{runs.length}</Label></TabTitleText>} />
                        <Tab eventKey="runs" title={<TabTitleText>Runs <Label isCompact>{bottomTabs.runsCount}</Label></TabTitleText>} />
                    </Tabs>
                ) : (
                    <>
                        <span className="axiom-workbench__pane-title">Test output</span>
                        <Label isCompact>{runs.length}</Label>
                    </>
                )}
                <span className="axiom-workbench__spacer" />
                {runsTab ? bottomTabs?.runsActions : actions}
            </div>
            {!collapsed && runsTab && <div className="axiom-test-strip__body">{bottomTabs?.runsContent}</div>}
            {!collapsed && !runsTab && (
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
                                    aria-label={run.status === "suggested" && onRunSuggestion
                                        ? `Run the suggested ${run.itemName} test`
                                        : `Show details of the ${run.itemName} test run`}
                                    onClick={() => activate(run)} onKeyDown={(e) => onRowKey(e, run)}>
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
                                    {onSendToAi && isFinishedRun(run) && (
                                        <Button variant="link" isInline size="sm" isDisabled={isSendToAiDisabled}
                                            aria-label={`Send the ${run.itemName} test run to the AI`}
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                onSendToAi(run);
                                            }}>Send to AI</Button>
                                    )}
                                </div>
                            </li>
                        ))}
                    </ul>
                </div>
            )}
            {selected && (
                <TestRunDetailModal run={selected} onClose={() => setSelectedId(null)} onOpenItem={onOpenItem}
                    onRunSuggestion={onRunSuggestion} onSendToAi={onSendToAi}
                    isSendToAiDisabled={isSendToAiDisabled} />
            )}
        </section>
    );
}
