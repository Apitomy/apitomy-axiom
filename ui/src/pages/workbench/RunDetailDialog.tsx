import { useCallback, useEffect, useRef, useState } from "react";
import {
    Alert, Button, Label, Modal, ModalBody, ModalFooter, ModalHeader, Tab, TabContentBody, Tabs, TabTitleText, Title,
} from "@patternfly/react-core";
import ExternalLinkAltIcon from "@patternfly/react-icons/dist/esm/icons/external-link-alt-icon";
import { WorkflowViewer } from "@apitomy/flow-ui";
import type { Workflow } from "@apitomy/flow-ui";
import {
    fetchSubjectTasks, fetchTaskExecutionLog, getWorkflowRun, type Task, type WorkflowInstanceInfo,
} from "../../config/api";
import { sseClient } from "../../config/sse";
import { useEffectiveTheme } from "../../hooks/useTheme";
import { STATUS_COLORS, toFlowInstance } from "../../components/workflowRun";
import { formatDuration } from "../../components/TraceGraphNode";
import { CodeBlock, prettyJson } from "./TestRunDetailModal";
import { runAnalysisPrefill } from "./runAnalysis";

/** Props for {@link RunDetailDialog}. */
export interface RunDetailDialogProps {
    runId: number;
    /** Name of the workflow, used in the Ask AI prompt. */
    workflowName: string;
    onClose(): void;
    /** Prefills the chat with the given text. */
    onAskAi(text: string): void;
}

/** Tabs of the run dialog. */
type RunDialogTab = "graph" | "failure" | "context";

const REFRESH_DEBOUNCE_MS: number = 300;

/**
 * The reason a failed run failed: its failure reason, or the output of its first failed task.
 * @param run the run
 * @param tasks the run's tasks
 * @returns the reason, if known
 */
function failureText(run: WorkflowInstanceInfo, tasks: Task[]): string | undefined {
    return run.failureReason || tasks.find((t) => t.status === "failed")?.output || undefined;
}

/** Text shown as highlighted JSON when it is JSON, otherwise as plain text. */
function TextBlock({ text, label }: { text: string; label: string }) {
    const json: string | null = prettyJson(text);
    return <CodeBlock code={json ?? text} language={json ? "json" : "plaintext"} label={label} />;
}

/** One failed task: its output and its execution log. */
function FailedTask({ task }: { task: Task }) {
    const [log, setLog] = useState<string | null>(null);
    useEffect(() => {
        let active: boolean = true;
        fetchTaskExecutionLog(task.subjectId, task.id)
            .then((text) => { if (active) setLog(text); })
            .catch((e: unknown) => { if (active) setLog(`Could not load the log: ${e instanceof Error ? e.message : String(e)}`); });
        return () => { active = false; };
    }, [task.subjectId, task.id]);
    const name: string = task.nodeId ?? task.actionType;
    return (
        <section className="axiom-test-run__section">
            <Title headingLevel="h3" size="md">{name} <Label isCompact color="red">{task.status}</Label></Title>
            {task.output ? <TextBlock text={task.output} label={`Output of ${name}`} />
                : <p className="axiom-text-subtle">(no output)</p>}
            <Title headingLevel="h4" size="md">Execution log</Title>
            {log === null ? <p className="axiom-text-subtle">Loading log...</p>
                : log ? <TextBlock text={log} label={`Log of ${name}`} />
                    : <p className="axiom-text-subtle">(empty)</p>}
        </section>
    );
}

/**
 * Shows one run of the composition's workflow: metadata, failure reason, graph, failed tasks and context. Kept
 * current via `workflow-updated` SSE events for the run.
 */
export function RunDetailDialog({ runId, workflowName, onClose, onAskAi }: RunDetailDialogProps) {
    const [run, setRun] = useState<WorkflowInstanceInfo | null>(null);
    const [tasks, setTasks] = useState<Task[]>([]);
    const [error, setError] = useState<string | null>(null);
    const [tab, setTab] = useState<RunDialogTab>("graph");
    const theme = useEffectiveTheme();

    // Only the latest load may update the state, so a slow earlier response cannot overwrite a newer one.
    const sequence = useRef<number>(0);

    const load = useCallback(() => {
        const request: number = ++sequence.current;
        const latest = (): boolean => request === sequence.current;
        getWorkflowRun(runId)
            .then((r) => {
                if (!latest()) {
                    return;
                }
                setRun(r);
                setError(null);
                fetchSubjectTasks(r.subjectId)
                    .then((all) => { if (latest()) setTasks(all.filter((t) => t.workflowRunId === runId)); })
                    .catch((e: unknown) => console.error("Could not load the run's tasks", e));
            })
            .catch((e: unknown) => { if (latest()) setError(e instanceof Error ? e.message : String(e)); });
    }, [runId]);

    useEffect(() => {
        load();
        let timer: ReturnType<typeof setTimeout> | undefined;
        const unsubscribe = sseClient.subscribe((event) => {
            if (event.type === "workflow-updated" && (event.data as { runId?: number }).runId === runId) {
                clearTimeout(timer);
                timer = setTimeout(load, REFRESH_DEBOUNCE_MS);
            }
        });
        return () => {
            clearTimeout(timer);
            unsubscribe();
        };
    }, [runId, load]);

    const failed: boolean = run?.status === "failed";
    const failedTasks: Task[] = tasks.filter((t) => t.status === "failed");
    const activeTab: RunDialogTab = tab === "failure" && !failed ? "graph" : tab;
    const askAi = () => {
        if (!run) {
            return;
        }
        onAskAi(runAnalysisPrefill({ id: runId, status: run.status, currentNodeName: run.currentNodeName,
            reason: failureText(run, tasks) }, workflowName || run.definitionName));
        onClose();
    };
    const title: string = run ? `Run #${runId}: ${run.title || `${run.definitionName} v${run.definitionVersion}`}`
        : `Run #${runId}`;
    return (
        <Modal isOpen variant="large" onClose={onClose} aria-labelledby="run-detail-title"
            elementToFocus="#run-detail-primary">
            <ModalHeader title={title} labelId="run-detail-title" />
            <ModalBody>
                {error && <Alert isInline variant="danger" title={`Could not load the run: ${error}`} />}
                {!run && !error && <p className="axiom-text-subtle">Loading run...</p>}
                {run && (
                    <>
                        <dl className="axiom-test-run__meta">
                            <div>
                                <dt>Status</dt>
                                <dd><Label isCompact color={STATUS_COLORS[run.status] || "grey"}>{run.status}</Label></dd>
                            </div>
                            <div>
                                <dt>Version</dt>
                                <dd>v{run.definitionVersion}</dd>
                            </div>
                            <div>
                                <dt>Started</dt>
                                <dd>{new Date(run.startedOn).toLocaleString()}</dd>
                            </div>
                            {run.completedOn && (
                                <div>
                                    <dt>Duration</dt>
                                    <dd>{formatDuration(Date.parse(run.completedOn) - Date.parse(run.startedOn))}</dd>
                                </div>
                            )}
                            {run.currentNodeName && (
                                <div>
                                    <dt>Current node</dt>
                                    <dd>{run.currentNodeName}</dd>
                                </div>
                            )}
                        </dl>
                        {run.failureReason && (
                            <Alert isInline variant="danger" title={run.failureReason} className="axiom-test-run__section" />
                        )}
                        <Tabs className="axiom-test-run__tabs" activeKey={activeTab} mountOnEnter
                            onSelect={(_e, key) => setTab(key as RunDialogTab)} aria-label="Run details">
                            <Tab eventKey="graph" title={<TabTitleText>Graph</TabTitleText>}>
                                <TabContentBody className="axiom-test-run__tab-body axiom-run-detail__graph">
                                    {run.workflowContent ? (
                                        <WorkflowViewer workflow={run.workflowContent as Workflow}
                                            instance={toFlowInstance(run)} theme={theme === "dark" ? "dark" : "light"} />
                                    ) : <p className="axiom-text-subtle">The run has no workflow content.</p>}
                                </TabContentBody>
                            </Tab>
                            {failed && (
                                <Tab eventKey="failure" title={<TabTitleText>Failure</TabTitleText>}>
                                    <TabContentBody className="axiom-test-run__tab-body">
                                        {failedTasks.length === 0
                                            ? <p className="axiom-text-subtle">No failed tasks.</p>
                                            : failedTasks.map((t) => <FailedTask key={t.id} task={t} />)}
                                    </TabContentBody>
                                </Tab>
                            )}
                            <Tab eventKey="context" title={<TabTitleText>Context</TabTitleText>}>
                                <TabContentBody className="axiom-test-run__tab-body">
                                    <CodeBlock code={JSON.stringify(run.context ?? {}, null, 2)} language="json"
                                        label="Context" />
                                </TabContentBody>
                            </Tab>
                        </Tabs>
                    </>
                )}
            </ModalBody>
            <ModalFooter>
                {failed && (
                    <Button id="run-detail-primary" variant="primary" onClick={askAi}>Ask AI to analyze</Button>
                )}
                <Button component="a" variant="secondary" href={`/runs/${runId}`} target="_blank" rel="noopener noreferrer"
                    icon={<ExternalLinkAltIcon />} iconPosition="end">Open run page</Button>
                <Button id={failed ? undefined : "run-detail-primary"} variant={failed ? "link" : "primary"}
                    onClick={onClose}>Close</Button>
            </ModalFooter>
        </Modal>
    );
}
