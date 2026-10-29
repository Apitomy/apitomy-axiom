import { useState, useEffect, useCallback, useMemo, type ReactNode } from "react";
import { useParams, Link, useNavigate } from "react-router-dom";
import {
    Breadcrumb, BreadcrumbItem, Button, CodeBlock, CodeBlockCode, Flex, FlexItem, Form, FormGroup, Label,
    PageSection, Tab, TabTitleText, Tabs, Title,
} from "@patternfly/react-core";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import { WorkflowViewer } from "@apitomy/flow-ui";
import type {
    Workflow, WorkflowInstance as FlowInstance, WorkflowViewerNodeMenuItem,
} from "@apitomy/flow-ui";
import { TraceGraph } from "../components/TraceGraph";
import { LineagePanel } from "../components/LineagePanel";
import { WorkflowStepTimeline } from "../components/WorkflowStepTimeline";
import { ExecutionLogModal } from "../components/ExecutionLogModal";
import { LabelDisplay } from "../components/LabelDisplay";
import { EditLabelsModal } from "../components/EditLabelsModal";
import {
    cancelWorkflowRun, fetchSubjectTasks, getWorkflowRun, setWorkflowRunLabels,
    type Task, type WorkflowInstanceInfo,
} from "../config/api";
import { sseClient, type AxiomSseEvent } from "../config/sse";
import { useEffectiveTheme } from "../hooks/useTheme";
import { loadEditorActionTypes } from "../hooks/useWorkflowEditorSpi";
import { buildRunNodeMenuItems } from "./workflowRunNodeMenu";
import { STATUS_COLORS, toFlowInstance } from "../components/workflowRun";

/**
 * One read-only field of the run Overview form.
 *
 * @param props the field label, its id and its content
 * @returns the form group
 */
function OverviewField({ label, id, children }: { label: string; id: string; children: ReactNode }) {
    return (
        <FormGroup label={label} fieldId={id}>
            <div id={id} className="axiom-run-overview__value">{children}</div>
        </FormGroup>
    );
}

/** Shows a single workflow run: diagram (default tab), overview, timeline, trace and lineage. */
export function WorkflowRunDetailPage() {
    const { runId } = useParams<{ runId: string }>();
    const navigate = useNavigate();
    const numericRunId = Number(runId);
    const [run, setRun] = useState<WorkflowInstanceInfo | null>(null);
    const [activeTab, setActiveTab] = useState(1);
    const [refreshKey, setRefreshKey] = useState(0);
    const [isLogOpen, setIsLogOpen] = useState(false);
    const [isLabelsOpen, setIsLabelsOpen] = useState(false);
    const [logTaskId, setLogTaskId] = useState<number | null>(null);
    const [tasks, setTasks] = useState<Task[]>([]);
    const effectiveTheme = useEffectiveTheme();

    const load = useCallback(() => {
        getWorkflowRun(numericRunId)
            .then((r) => {
                setRun(r);
                return fetchSubjectTasks(r.subjectId);
            })
            .then((all) => setTasks(all.filter((t) => t.workflowRunId === numericRunId)))
            .catch(console.error);
    }, [numericRunId]);

    useEffect(() => { load(); }, [load]);

    useEffect(() => {
        let timeout: ReturnType<typeof setTimeout>;
        const unsubscribe = sseClient.subscribe((event: AxiomSseEvent) => {
            if (event.type === "workflow-updated"
                    && (event.data as { runId?: number }).runId === numericRunId) {
                clearTimeout(timeout);
                timeout = setTimeout(() => { load(); setRefreshKey((k) => k + 1); }, 300);
            }
        });
        return () => { clearTimeout(timeout); unsubscribe(); };
    }, [numericRunId, load]);

    const workflowContent = useMemo(
        () => (run?.workflowContent as Workflow | undefined), [run]);
    const viewerInstance = useMemo<FlowInstance | undefined>(
        () => (run ? toFlowInstance(run) : undefined), [run]);

    if (!run) {
        return <PageSection><p>Loading workflow run...</p></PageSection>;
    }

    const openLog = (taskId: number) => { setLogTaskId(taskId); setIsLogOpen(true); };

    // Host-contributed right-click actions for a viewer node: open that node's task execution log, jump to
    // the run's execution trace tab, or open the node in the workflow editor.
    const nodeMenuItems = (nodeId: string): WorkflowViewerNodeMenuItem[] =>
        buildRunNodeMenuItems(run, nodeId, {
            openLog,
            viewTrace: () => setActiveTab(3),
            openInEditor: (url: string) => navigate(url),
        });

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: "12px" }}>
                <BreadcrumbItem render={() => (
                    <Link to="/runs">Runs</Link>)} />
                <BreadcrumbItem isActive>Run #{run.runId}</BreadcrumbItem>
            </Breadcrumb>

            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}>
                <FlexItem>
                    <Title headingLevel="h1" size="lg">
                        {run.title || `${run.subjectName || `Subject #${run.subjectId}`} — `
                            + `${run.definitionName || `Workflow #${run.definitionId}`} v${run.definitionVersion}`}{" "}
                        <Label color={STATUS_COLORS[run.status] || "grey"}>
                            {run.status}
                        </Label>
                    </Title>
                </FlexItem>
                <FlexItem>
                    {(run.status === "running" || run.status === "waiting") && (
                        <Button variant="secondary" style={{ marginRight: "8px" }}
                            onClick={() => cancelWorkflowRun(numericRunId).then(load).catch(console.error)}>
                            Cancel run
                        </Button>
                    )}
                    <Button variant="control" aria-label="Refresh"
                        onClick={() => { load(); setRefreshKey((k) => k + 1); }}>
                        <SyncAltIcon />
                    </Button>
                </FlexItem>
            </Flex>

            <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                <Tab eventKey={1} title={<TabTitleText>Diagram</TabTitleText>} />
                <Tab eventKey={0} title={<TabTitleText>Overview</TabTitleText>} />
                <Tab eventKey={2} title={<TabTitleText>Timeline</TabTitleText>} />
                <Tab eventKey={3} title={<TabTitleText>Execution Trace</TabTitleText>} />
                <Tab eventKey={4} title={<TabTitleText>Lineage</TabTitleText>} />
            </Tabs>

            <div style={{ paddingTop: "16px" }}>
                {activeTab === 0 && (
                    <Form isHorizontal onSubmit={(e) => e.preventDefault()} className="axiom-run-overview">
                        {run.title && (
                            <OverviewField label="Title" id="run-title">{run.title}</OverviewField>
                        )}
                        <OverviewField label="Subject" id="run-subject">
                            <Link to={`/subjects/${run.subjectId}`}>{run.subjectName || `Subject #${run.subjectId}`}</Link>
                        </OverviewField>
                        <OverviewField label="Workflow" id="run-workflow">
                            <Link to={`/components/workflows/${run.definitionId}`}>
                                {run.definitionName || `Workflow #${run.definitionId}`}
                            </Link>
                            {` (version ${run.definitionVersion})`}
                        </OverviewField>
                        <OverviewField label="Status" id="run-status">
                            <Label isCompact color={STATUS_COLORS[run.status] || "grey"}>{run.status}</Label>
                        </OverviewField>
                        <OverviewField label="Started" id="run-started">
                            {new Date(run.startedOn).toLocaleString()}
                        </OverviewField>
                        <OverviewField label="Completed" id="run-completed">
                            {run.completedOn ? new Date(run.completedOn).toLocaleString() : "—"}
                        </OverviewField>
                        {run.failureReason && (
                            <OverviewField label="Failure" id="run-failure">{run.failureReason}</OverviewField>
                        )}
                        <OverviewField label="Triggered by" id="run-trigger">
                            {run.triggerEventId ? (
                                <Link to={`/events/stream/${run.triggerEventId}`}>Event {run.triggerEventId}</Link>
                            ) : "Started manually or by another means"}
                        </OverviewField>
                        {run.resumedBy && run.resumedBy.length > 0 && (
                            <OverviewField label="Resumed by" id="run-resumed">
                                {run.resumedBy.map((r, i) => (
                                    <div key={`${i}-${r.eventId}-${r.resumedOn}`}>
                                        <Link to={`/events/stream/${r.eventId}`}>Event {r.eventId}</Link>
                                        {" "}at {r.nodeName || r.nodeId}
                                        {" "}({new Date(r.resumedOn).toLocaleString()})
                                    </div>
                                ))}
                            </OverviewField>
                        )}
                        {run.traceId && (
                            <OverviewField label="Trace" id="run-trace">
                                <Link to={`/logs/traces/${run.traceId}`}>View full execution trace →</Link>
                            </OverviewField>
                        )}
                        <OverviewField label="Labels" id="run-labels">
                            <LabelDisplay labels={run.labels ?? []} onEdit={() => setIsLabelsOpen(true)} />
                        </OverviewField>
                        <OverviewField label="Inputs" id="run-inputs">
                            {Object.keys(run.inputs ?? {}).length === 0 ? "—" : (
                                <CodeBlock>
                                    <CodeBlockCode>{JSON.stringify(run.inputs, null, 2)}</CodeBlockCode>
                                </CodeBlock>
                            )}
                        </OverviewField>
                        <OverviewField label="Tasks" id="run-tasks">
                            {tasks.length === 0 ? "—" : tasks.map((t) => (
                                <div key={t.id} style={{ marginBottom: "8px" }}>
                                    <strong>{t.nodeId ?? t.actionType}</strong>{" "}
                                    <Label isCompact>{t.status}</Label>{" "}
                                    <Button variant="link" isInline onClick={() => openLog(t.id)}>log</Button>
                                    <pre style={{ whiteSpace: "pre-wrap" }}>{t.output || "(no output)"}</pre>
                                </div>
                            ))}
                        </OverviewField>
                    </Form>
                )}

                {activeTab === 1 && workflowContent && viewerInstance && (
                    <div style={{ height: "calc(100vh - 320px)", minHeight: "500px" }}>
                        <WorkflowViewer
                            workflow={workflowContent}
                            instance={viewerInstance}
                            theme={effectiveTheme === "dark" ? "dark" : "light"}
                            nodeContextMenuItems={nodeMenuItems}
                            actionTypes={loadEditorActionTypes}
                        />
                    </div>
                )}

                {activeTab === 2 && (
                    <WorkflowStepTimeline
                        history={run.history ?? []}
                        traceId={run.traceId}
                        onViewLog={openLog}
                    />
                )}

                {activeTab === 3 && run.traceId && (
                    <div style={{ height: "calc(100vh - 320px)", minHeight: "500px" }}>
                        <TraceGraph traceId={run.traceId} refreshKey={refreshKey} />
                    </div>
                )}
                {activeTab === 3 && !run.traceId && (
                    <p>No execution trace is available for this run.</p>
                )}

                {activeTab === 4 && run.runId != null && (
                    <LineagePanel entityType="workflow-run" id={run.runId} expandable={false} />
                )}
            </div>

            <EditLabelsModal
                isOpen={isLabelsOpen}
                labels={run.labels ?? []}
                onSave={async (labels) => {
                    const stored: string[] = await setWorkflowRunLabels(run.runId ?? run.id, labels);
                    setRun((prev) => prev ? { ...prev, labels: stored } : prev);
                }}
                onClose={() => setIsLabelsOpen(false)}
            />
            <ExecutionLogModal
                isOpen={isLogOpen}
                subjectId={run.subjectId}
                taskId={logTaskId}
                onClose={() => setIsLogOpen(false)}
            />
        </PageSection>
    );
}
