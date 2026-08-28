import { useState, useEffect, useCallback, useMemo } from "react";
import { useParams, Link } from "react-router-dom";
import {
    Breadcrumb, BreadcrumbItem, Button, Flex, FlexItem, Label,
    PageSection, Tab, TabTitleText, Tabs, Title,
} from "@patternfly/react-core";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import { WorkflowViewer } from "@apitomy/flow-ui";
import type {
    Workflow, WorkflowInstance as FlowInstance, WorkflowViewerNodeMenuItem,
} from "@apitomy/flow-ui";
import { TraceGraph } from "../components/TraceGraph";
import { WorkflowStepTimeline } from "../components/WorkflowStepTimeline";
import { ExecutionLogModal } from "../components/ExecutionLogModal";
import { getWorkflowRun, type WorkflowInstanceInfo } from "../config/api";
import { sseClient, type AxiomSseEvent } from "../config/sse";
import { useEffectiveTheme } from "../hooks/useTheme";

const STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    running: "blue", waiting: "orange", completed: "green",
    failed: "red", cancelled: "grey",
};

export function WorkflowRunDetailPage() {
    const { runId } = useParams<{ runId: string }>();
    const numericRunId = Number(runId);
    const [run, setRun] = useState<WorkflowInstanceInfo | null>(null);
    const [activeTab, setActiveTab] = useState(0);
    const [refreshKey, setRefreshKey] = useState(0);
    const [isLogOpen, setIsLogOpen] = useState(false);
    const [logTaskId, setLogTaskId] = useState<number | null>(null);
    const effectiveTheme = useEffectiveTheme();

    const load = useCallback(() => {
        getWorkflowRun(numericRunId).then(setRun).catch(console.error);
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
    const viewerInstance = useMemo<FlowInstance | undefined>(() => {
        if (!run) return undefined;
        return {
            id: String(run.id),
            workflowId: String(run.definitionId),
            currentNodeId: run.currentNodeId ?? "",
            status: run.status as any,
            context: run.context || {},
            history: (run.history ?? []).map((h) => ({
                nodeId: h.nodeId, nodeName: h.nodeName,
                edgeId: h.edgeId ?? "", edgeCondition: h.edgeCondition ?? "",
                enteredOn: h.enteredOn, completedOn: h.completedOn ?? "",
                output: h.output ?? {},
            })),
            failureReason: run.failureReason,
            createdOn: run.startedOn,
            updatedOn: run.completedOn ?? run.startedOn,
        } as FlowInstance;
    }, [run]);

    if (!run) {
        return <PageSection><p>Loading workflow run...</p></PageSection>;
    }

    const openLog = (taskId: number) => { setLogTaskId(taskId); setIsLogOpen(true); };

    // Host-contributed right-click actions for a viewer node: open that node's
    // task execution log, and jump to the run's execution trace tab.
    const nodeMenuItems = (nodeId: string): WorkflowViewerNodeMenuItem[] => {
        const items: WorkflowViewerNodeMenuItem[] = [];
        const entry = (run.history ?? []).find((h) => h.nodeId === nodeId);
        if (entry?.taskId != null) {
            const taskId = entry.taskId;
            items.push({
                id: "open-log",
                label: "Open execution log",
                onSelect: () => openLog(taskId),
            });
        }
        if (run.traceId) {
            items.push({
                id: "view-trace",
                label: "View in execution trace",
                onSelect: () => setActiveTab(3),
            });
        }
        return items;
    };

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: "12px" }}>
                <BreadcrumbItem render={() => (
                    <Link to="/logs/workflow-runs">Workflow Runs</Link>)} />
                <BreadcrumbItem isActive>Run #{run.runId}</BreadcrumbItem>
            </Breadcrumb>

            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}>
                <FlexItem>
                    <Title headingLevel="h1" size="lg">
                        {run.definitionName} v{run.definitionVersion}{" "}
                        <Label color={STATUS_COLORS[run.status] || "grey"}>
                            {run.status}
                        </Label>
                    </Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="control" aria-label="Refresh"
                        onClick={() => { load(); setRefreshKey((k) => k + 1); }}>
                        <SyncAltIcon />
                    </Button>
                </FlexItem>
            </Flex>

            <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                <Tab eventKey={0} title={<TabTitleText>Overview</TabTitleText>} />
                <Tab eventKey={1} title={<TabTitleText>Diagram</TabTitleText>} />
                <Tab eventKey={2} title={<TabTitleText>Timeline</TabTitleText>} />
                <Tab eventKey={3} title={<TabTitleText>Execution Trace</TabTitleText>} />
            </Tabs>

            <div style={{ paddingTop: "16px" }}>
                {activeTab === 0 && (
                    <dl>
                        <dt><strong>Workflow</strong></dt>
                        <dd>{run.definitionName} v{run.definitionVersion}</dd>
                        <dt><strong>Status</strong></dt>
                        <dd>{run.status}</dd>
                        <dt><strong>Started</strong></dt>
                        <dd>{new Date(run.startedOn).toLocaleString()}</dd>
                        <dt><strong>Completed</strong></dt>
                        <dd>{run.completedOn
                            ? new Date(run.completedOn).toLocaleString() : "—"}</dd>
                        {run.failureReason && (
                            <>
                                <dt><strong>Failure</strong></dt>
                                <dd>{run.failureReason}</dd>
                            </>
                        )}
                        {run.traceId && (
                            <>
                                <dt><strong>Trace</strong></dt>
                                <dd>
                                    <Link to={`/logs/traces/${run.traceId}`}>
                                        View full execution trace →
                                    </Link>
                                </dd>
                            </>
                        )}
                    </dl>
                )}

                {activeTab === 1 && workflowContent && viewerInstance && (
                    <div style={{ height: "calc(100vh - 320px)", minHeight: "500px" }}>
                        <WorkflowViewer
                            workflow={workflowContent}
                            instance={viewerInstance}
                            theme={effectiveTheme === "dark" ? "dark" : "light"}
                            nodeContextMenuItems={nodeMenuItems}
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
            </div>

            <ExecutionLogModal
                isOpen={isLogOpen}
                projectId={run.projectId}
                taskId={logTaskId}
                onClose={() => setIsLogOpen(false)}
            />
        </PageSection>
    );
}
