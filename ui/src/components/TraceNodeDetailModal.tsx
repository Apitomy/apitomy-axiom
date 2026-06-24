import { useEffect, useState } from "react";
import {
    Button,
    DescriptionList,
    DescriptionListDescription,
    DescriptionListGroup,
    DescriptionListTerm,
    EmptyState,
    EmptyStateBody,
    Label,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
} from "@patternfly/react-core";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { fetchTraceNodeDetail, type TraceNode } from "../config/api";
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
                <DescriptionList isHorizontal isCompact>
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
                ) : detail && (
                    <div style={{ marginTop: "16px" }}>
                        {renderDetail(node!.entityType, detail)}
                    </div>
                )}
            </ModalBody>
            <ModalFooter>
                <Button variant="link" onClick={onClose}>Close</Button>
            </ModalFooter>
        </Modal>
    );
}

function renderDetail(entityType: string | undefined, detail: Record<string, unknown>) {
    switch (entityType) {
        case "tool-execution":
            return <ToolExecutionDetail detail={detail} />;
        case "activity-log":
            return <ActivityLogDetail detail={detail} />;
        case "ai-usage":
            return <AiUsageDetail detail={detail} />;
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

function ToolExecutionDetail({ detail }: { detail: Record<string, unknown> }) {
    return (
        <>
            <DescriptionList isHorizontal isCompact style={{ marginBottom: "12px" }}>
                <DescriptionListGroup>
                    <DescriptionListTerm>Tool</DescriptionListTerm>
                    <DescriptionListDescription>
                        <Label isCompact>{String(detail.toolName || "")}</Label>
                    </DescriptionListDescription>
                </DescriptionListGroup>
                <DescriptionListGroup>
                    <DescriptionListTerm>Status</DescriptionListTerm>
                    <DescriptionListDescription>{String(detail.status || "")}</DescriptionListDescription>
                </DescriptionListGroup>
                {detail.durationMs != null && (
                    <DescriptionListGroup>
                        <DescriptionListTerm>Duration</DescriptionListTerm>
                        <DescriptionListDescription>
                            {formatDuration(Number(detail.durationMs))}
                        </DescriptionListDescription>
                    </DescriptionListGroup>
                )}
            </DescriptionList>
            {detail.toolInput && (
                <>
                    <h4 style={{ marginBottom: "4px" }}>Input</h4>
                    <CodeEditor
                        code={formatJson(String(detail.toolInput))}
                        language={Language.json}
                        isReadOnly
                        height="200px"
                    />
                </>
            )}
            {detail.toolOutput && (
                <>
                    <h4 style={{ margin: "12px 0 4px" }}>Output</h4>
                    <CodeEditor
                        code={formatJson(String(detail.toolOutput))}
                        language={Language.json}
                        isReadOnly
                        height="200px"
                    />
                </>
            )}
        </>
    );
}

function ActivityLogDetail({ detail }: { detail: Record<string, unknown> }) {
    return (
        <>
            {detail.summary && (
                <p style={{ marginBottom: "12px" }}>{String(detail.summary)}</p>
            )}
            {detail.details && (
                <CodeEditor
                    code={String(detail.details)}
                    language={Language.markdown}
                    isReadOnly
                    height="400px"
                />
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
                <CodeEditor
                    code={formatJson(String(detail.payload))}
                    language={Language.json}
                    isReadOnly
                    height="400px"
                />
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
