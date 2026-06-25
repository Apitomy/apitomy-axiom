import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
    DescriptionList,
    DescriptionListDescription,
    DescriptionListGroup,
    DescriptionListTerm,
    EmptyState,
    EmptyStateBody,
    Label,
    Modal,
    ModalBody,
    ModalHeader,
    Tab,
    Tabs,
    TabTitleText,
} from "@patternfly/react-core";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { fetchTraceNodeDetail, fetchTools, type TraceNode } from "../config/api";
import { formatDuration, STATUS_COLORS } from "./TraceGraphNode";

interface TraceNodeDetailModalProps {
    isOpen: boolean;
    traceId: string;
    node: TraceNode | null;
    onClose: () => void;
}

export function TraceNodeDetailModal({ isOpen, traceId, node, onClose }: TraceNodeDetailModalProps) {
    const [loading, setLoading] = useState(false);
    const [detail, setDetail] = useState<Record<string, unknown> | null>(null);

    useEffect(() => {
        if (!isOpen || !node) return;
        setLoading(true);
        setDetail(null);
        fetchTraceNodeDetail(traceId, node.id)
            .then((data) => setDetail(data.detail))
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [isOpen, traceId, node]);

    return (
        <Modal isOpen={isOpen} onClose={onClose} variant="large" aria-label="Node Detail">
            <ModalHeader title={node?.summary || "Node Detail"} />
            <ModalBody>
                <DescriptionList isHorizontal isCompact columnModifier={{ default: "2Col" }}>
                    <DescriptionListGroup>
                        <DescriptionListTerm>Type</DescriptionListTerm>
                        <DescriptionListDescription>
                            <Label isCompact>{node?.nodeType}</Label>
                        </DescriptionListDescription>
                    </DescriptionListGroup>
                    <DescriptionListGroup>
                        <DescriptionListTerm>Status</DescriptionListTerm>
                        <DescriptionListDescription>
                            <Label isCompact color={STATUS_COLORS[node?.status || ""]}>
                                {node?.status}
                            </Label>
                        </DescriptionListDescription>
                    </DescriptionListGroup>
                    <DescriptionListGroup>
                        <DescriptionListTerm>Duration</DescriptionListTerm>
                        <DescriptionListDescription>
                            {node?.durationMs != null ? formatDuration(node.durationMs) : "—"}
                        </DescriptionListDescription>
                    </DescriptionListGroup>
                    <DescriptionListGroup>
                        <DescriptionListTerm>Started</DescriptionListTerm>
                        <DescriptionListDescription>
                            {node ? new Date(node.startedOn).toLocaleString() : "—"}
                        </DescriptionListDescription>
                    </DescriptionListGroup>
                    {node?.completedOn && (
                        <DescriptionListGroup>
                            <DescriptionListTerm>Completed</DescriptionListTerm>
                            <DescriptionListDescription>
                                {new Date(node.completedOn).toLocaleString()}
                            </DescriptionListDescription>
                        </DescriptionListGroup>
                    )}
                </DescriptionList>

                {loading ? (
                    <EmptyState style={{ marginTop: "16px" }}>
                        <EmptyStateBody>Loading details...</EmptyStateBody>
                    </EmptyState>
                ) : detail && node && (
                    <div style={{ marginTop: "16px" }}>
                        {renderDetail(node.nodeType, node.entityType, detail)}
                    </div>
                )}
            </ModalBody>
        </Modal>
    );
}

function renderDetail(nodeType: string, entityType: string | undefined,
        detail: Record<string, unknown>) {
    if (nodeType === "decision-processed" && entityType === "activity-log") {
        return <DecisionDetail detail={detail} />;
    }
    if (nodeType === "report-ai-invoked" && entityType === "report") {
        return <ReportExecutionLogDetail detail={detail} />;
    }
    if (nodeType === "report-triggered" && entityType === "report") {
        return <ReportLinkDetail detail={detail} />;
    }
    if ((nodeType === "event-ignored" || nodeType === "escalation") && entityType === "activity-log") {
        return <ReasoningDetail detail={detail} />;
    }
    switch (entityType) {
        case "tool-execution":
            return <ToolExecutionDetail detail={detail} />;
        case "activity-log":
            return <ActivityLogDetail detail={detail} />;
        case "ai-usage":
            return <AiUsageDetail detail={detail} />;
        case "task":
            return <TaskDetail detail={detail} />;
        case "event":
            return <EventDetail detail={detail} />;
        default:
            return (
                <CodeEditor
                    code={JSON.stringify(detail, null, 2)}
                    language={Language.json}
                    isReadOnly
                    height="400px"
                />
            );
    }
}

function ReasoningDetail({ detail }: { detail: Record<string, unknown> }) {
    return (
        <>
            <h4 style={{ marginBottom: "4px", fontWeight: "bold" }}>Reasoning</h4>
            <CodeEditor
                code={String(detail.summary || "")}
                language={Language.plaintext}
                isReadOnly
                height="200px"
                options={{ wordWrap: "on" }}
            />
        </>
    );
}

function ReportLinkDetail({ detail }: { detail: Record<string, unknown> }) {
    const reportId = detail.id;
    const title = detail.title ? String(detail.title) : `Report #${reportId}`;
    return (
        <DescriptionList isHorizontal isCompact>
            <DescriptionListGroup>
                <DescriptionListTerm>Report</DescriptionListTerm>
                <DescriptionListDescription>
                    <Link to={`/reports/${reportId}`}>{title}</Link>
                </DescriptionListDescription>
            </DescriptionListGroup>
        </DescriptionList>
    );
}

function ReportExecutionLogDetail({ detail }: { detail: Record<string, unknown> }) {
    return (
        <>
            <h4 style={{ marginBottom: "4px", fontWeight: "bold" }}>Execution Log</h4>
            <CodeEditor
                code={String(detail.executionLog || "")}
                language={Language.markdown}
                isReadOnly
                height="400px"
            />
        </>
    );
}

function DecisionDetail({ detail }: { detail: Record<string, unknown> }) {
    return (
        <>
            {detail.summary && (
                <>
                    <h4 style={{ marginBottom: "4px", fontWeight: "bold" }}>Reasoning</h4>
                    <p style={{ marginBottom: "16px" }}>{String(detail.summary)}</p>
                </>
            )}
            {detail.details && (
                <>
                    <h4 style={{ marginBottom: "4px", fontWeight: "bold" }}>Input Context</h4>
                    <CodeEditor
                        code={String(detail.details)}
                        language={Language.markdown}
                        isReadOnly
                        height="300px"
                    />
                </>
            )}
        </>
    );
}

function ToolExecutionDetail({ detail }: { detail: Record<string, unknown> }) {
    const [toolId, setToolId] = useState<number | null>(null);
    const [activeTab, setActiveTab] = useState(0);
    const toolName = String(detail.toolName || "");

    useEffect(() => {
        if (!toolName) return;
        fetchTools(1, 1, toolName)
            .then((results) => {
                const match = results.items.find((t) => t.name === toolName);
                if (match) setToolId(match.id);
            })
            .catch(() => {});
    }, [toolName]);

    return (
        <>
            <DescriptionList isHorizontal isCompact style={{ marginBottom: "12px" }}>
                <DescriptionListGroup>
                    <DescriptionListTerm>Tool</DescriptionListTerm>
                    <DescriptionListDescription>
                        {toolId ? (
                            <Link to={`/tools/${toolId}`}>{toolName}</Link>
                        ) : toolName}
                    </DescriptionListDescription>
                </DescriptionListGroup>
            </DescriptionList>
            <Tabs activeKey={activeTab}
                onSelect={(_e, key) => setActiveTab(Number(key))}>
                <Tab eventKey={0} title={<TabTitleText>Input</TabTitleText>}>
                    <div style={{ marginTop: "8px" }}>
                        <CodeEditor
                            code={formatJson(String(detail.toolInput || "{}"))}
                            language={Language.json}
                            isReadOnly
                            height="300px"
                            options={{ wordWrap: "on" }}
                        />
                    </div>
                </Tab>
                <Tab eventKey={1} title={<TabTitleText>Output</TabTitleText>}>
                    <div style={{ marginTop: "8px" }}>
                        <CodeEditor
                            code={formatJson(String(detail.toolOutput || ""))}
                            language={Language.json}
                            isReadOnly
                            height="300px"
                            options={{ wordWrap: "on" }}
                        />
                    </div>
                </Tab>
            </Tabs>
        </>
    );
}

function TaskDetail({ detail }: { detail: Record<string, unknown> }) {
    const [activeTab, setActiveTab] = useState(0);

    return (
        <Tabs activeKey={activeTab}
            onSelect={(_e, key) => setActiveTab(Number(key))}>
            <Tab eventKey={0} title={<TabTitleText>Input</TabTitleText>}>
                <div style={{ marginTop: "8px" }}>
                    <CodeEditor
                        code={String(detail.input || "")}
                        language={Language.markdown}
                        isReadOnly
                        height="300px"
                        options={{ wordWrap: "on" }}
                    />
                </div>
            </Tab>
            <Tab eventKey={1} title={<TabTitleText>Output</TabTitleText>}>
                <div style={{ marginTop: "8px" }}>
                    <CodeEditor
                        code={String(detail.output || "")}
                        language={Language.markdown}
                        isReadOnly
                        height="300px"
                        options={{ wordWrap: "on" }}
                    />
                </div>
            </Tab>
            <Tab eventKey={2} title={<TabTitleText>Execution Log</TabTitleText>}>
                <div style={{ marginTop: "8px" }}>
                    <CodeEditor
                        code={String(detail.executionLog || "")}
                        language={Language.markdown}
                        isReadOnly
                        height="400px"
                    />
                </div>
            </Tab>
        </Tabs>
    );
}

function ActivityLogDetail({ detail }: { detail: Record<string, unknown> }) {
    return (
        <>
            {detail.summary && (
                <DescriptionList isHorizontal isCompact style={{ marginBottom: "12px" }}>
                    <DescriptionListGroup>
                        <DescriptionListTerm>Manager decisions</DescriptionListTerm>
                        <DescriptionListDescription>{String(detail.summary)}</DescriptionListDescription>
                    </DescriptionListGroup>
                </DescriptionList>
            )}
            {detail.details && (
                <>
                    <h4 style={{ marginBottom: "4px", fontWeight: "bold" }}>Execution Log</h4>
                    <CodeEditor
                        code={String(detail.details)}
                        language={Language.markdown}
                        isReadOnly
                        height="400px"
                    />
                </>
            )}
        </>
    );
}

function AiUsageDetail({ detail }: { detail: Record<string, unknown> }) {
    return (
        <DescriptionList isHorizontal isCompact>
            {detail.invocationType != null && (
                <DescriptionListGroup>
                    <DescriptionListTerm>Type</DescriptionListTerm>
                    <DescriptionListDescription>{String(detail.invocationType)}</DescriptionListDescription>
                </DescriptionListGroup>
            )}
            {detail.costUsd != null && (
                <DescriptionListGroup>
                    <DescriptionListTerm>Cost</DescriptionListTerm>
                    <DescriptionListDescription>{"$" + Number(detail.costUsd).toFixed(4)}</DescriptionListDescription>
                </DescriptionListGroup>
            )}
            {detail.inputTokens != null && (
                <DescriptionListGroup>
                    <DescriptionListTerm>Input Tokens</DescriptionListTerm>
                    <DescriptionListDescription>
                        {String(Number(detail.inputTokens).toLocaleString())}
                    </DescriptionListDescription>
                </DescriptionListGroup>
            )}
            {detail.outputTokens != null && (
                <DescriptionListGroup>
                    <DescriptionListTerm>Output Tokens</DescriptionListTerm>
                    <DescriptionListDescription>
                        {String(Number(detail.outputTokens).toLocaleString())}
                    </DescriptionListDescription>
                </DescriptionListGroup>
            )}
        </DescriptionList>
    );
}

function EventDetail({ detail }: { detail: Record<string, unknown> }) {
    return (
        <>
            <DescriptionList isHorizontal isCompact style={{ marginBottom: "12px" }}>
                {detail.source != null && (
                    <DescriptionListGroup>
                        <DescriptionListTerm>Source</DescriptionListTerm>
                        <DescriptionListDescription>{String(detail.source)}</DescriptionListDescription>
                    </DescriptionListGroup>
                )}
                {detail.eventType != null && (
                    <DescriptionListGroup>
                        <DescriptionListTerm>Event Type</DescriptionListTerm>
                        <DescriptionListDescription>{String(detail.eventType)}</DescriptionListDescription>
                    </DescriptionListGroup>
                )}
                {detail.issueRef != null && (
                    <DescriptionListGroup>
                        <DescriptionListTerm>Issue</DescriptionListTerm>
                        <DescriptionListDescription>{String(detail.issueRef)}</DescriptionListDescription>
                    </DescriptionListGroup>
                )}
            </DescriptionList>
            {detail.payload && (
                <>
                    <h4 style={{ marginBottom: "4px", fontWeight: "bold" }}>Event Payload</h4>
                    <CodeEditor
                        code={formatJson(String(detail.payload))}
                        language={Language.json}
                        isReadOnly
                        height="400px"
                    />
                </>
            )}
        </>
    );
}

function formatJson(value: string): string {
    try {
        return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
        return value;
    }
}
