import { useState, useEffect, useCallback } from "react";
import { useParams, Link } from "react-router-dom";
import {
    Breadcrumb,
    BreadcrumbItem,
    DataList,
    DataListCell,
    DataListContent,
    DataListItem,
    DataListItemCells,
    DataListItemRow,
    DataListToggle,
    DescriptionList,
    DescriptionListDescription,
    DescriptionListGroup,
    DescriptionListTerm,
    EmptyState,
    EmptyStateBody,
    Label,
    PageSection,
    Tab,
    TabContent,
    TabTitleText,
    Tabs,
    Title,
} from "@patternfly/react-core";
import { CheckCircleIcon, TimesCircleIcon } from "@patternfly/react-icons";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { useEffectiveTheme } from "../hooks/useTheme";
import {
    type StreamEvent,
    type EventProcessingEntry,
    type EventProcessingOutcome,
    type EventProcessingOutcomeItem,
    fetchStreamEvent,
    fetchEventProcessing,
} from "../config/api";

const SOURCE_COLORS: Record<string, "blue" | "green" | "orange" | "grey"> = {
    github: "blue",
    jira: "green",
};

const STATUS_COLORS: Record<string, "green" | "grey" | "red" | "yellow"> = {
    completed: "green",
    skipped: "grey",
    failed: "red",
    pending: "yellow",
};

export function EventDetailPage() {
    const { eventId } = useParams<{ eventId: string }>();
    const effectiveTheme = useEffectiveTheme();

    const [event, setEvent] = useState<StreamEvent | null>(null);
    const [processing, setProcessing] = useState<EventProcessingEntry[]>([]);
    const [loading, setLoading] = useState(true);
    const [processingLoading, setProcessingLoading] = useState(false);
    const [activeTab, setActiveTab] = useState(0);

    const loadEvent = useCallback(() => {
        if (!eventId) return;
        setLoading(true);
        fetchStreamEvent(eventId)
            .then(setEvent)
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [eventId]);

    const loadProcessing = useCallback(() => {
        if (!eventId) return;
        setProcessingLoading(true);
        fetchEventProcessing(eventId)
            .then((result) => setProcessing(result.items))
            .catch(console.error)
            .finally(() => setProcessingLoading(false));
    }, [eventId]);

    useEffect(() => { loadEvent(); }, [loadEvent]);

    // Load processing data when switching to the Processing tab
    useEffect(() => {
        if (activeTab === 1) {
            loadProcessing();
        }
    }, [activeTab, loadProcessing]);

    if (loading) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Loading event...</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    if (!event) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Event not found.</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: "16px" }}>
                <BreadcrumbItem><Link to="/events/stream">Event Stream</Link></BreadcrumbItem>
                <BreadcrumbItem isActive>{event.type}</BreadcrumbItem>
            </Breadcrumb>

            <Title headingLevel="h1" size="lg" style={{ marginBottom: "16px" }}>
                {event.type}
            </Title>

            <DescriptionList isHorizontal isCompact style={{ marginBottom: "24px" }}>
                <DescriptionListGroup>
                    <DescriptionListTerm>Source</DescriptionListTerm>
                    <DescriptionListDescription>
                        <Label isCompact color={SOURCE_COLORS[event.source] || "grey"}>
                            {event.source}
                        </Label>
                    </DescriptionListDescription>
                </DescriptionListGroup>
                <DescriptionListGroup>
                    <DescriptionListTerm>Connection</DescriptionListTerm>
                    <DescriptionListDescription>
                        <Link to={`/events/connections/${event.connectionId}`}>{event.connectionId}</Link>
                    </DescriptionListDescription>
                </DescriptionListGroup>
                <DescriptionListGroup>
                    <DescriptionListTerm>Ref</DescriptionListTerm>
                    <DescriptionListDescription>
                        {event.ref ? (
                            <a href={event.ref} target="_blank" rel="noopener noreferrer">
                                {event.ref}
                            </a>
                        ) : "---"}
                    </DescriptionListDescription>
                </DescriptionListGroup>
                <DescriptionListGroup>
                    <DescriptionListTerm>Timestamp</DescriptionListTerm>
                    <DescriptionListDescription>
                        {new Date(event.timestamp).toLocaleString()}
                    </DescriptionListDescription>
                </DescriptionListGroup>
                <DescriptionListGroup>
                    <DescriptionListTerm>Actor</DescriptionListTerm>
                    <DescriptionListDescription>
                        {(event.actor?.login as string)
                            || (event.actor?.displayName as string)
                            || "---"}
                    </DescriptionListDescription>
                </DescriptionListGroup>
            </DescriptionList>

            <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                <Tab eventKey={0} title={<TabTitleText>Event</TabTitleText>}>
                    <TabContent id="event-tab" eventKey={0} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <CodeEditor
                            code={JSON.stringify(event, null, 2)}
                            language={Language.json}
                            isDarkTheme={effectiveTheme === "dark"}
                            height="600px"
                            isReadOnly
                            isLineNumbersVisible
                        />
                    </TabContent>
                </Tab>
                <Tab eventKey={1} title={<TabTitleText>Processing</TabTitleText>}>
                    <TabContent id="processing-tab" eventKey={1} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <ProcessingTab
                            entries={processing}
                            loading={processingLoading}
                        />
                    </TabContent>
                </Tab>
            </Tabs>
        </PageSection>
    );
}

const ROUTING_LABELS: Record<string, string> = {
    manager: "Send to Manager",
    "workflow-dispatch": "Dispatch to Workflows",
    "create-workflow": "Create Workflow",
    "invoke-action": "Invoke Action",
    processing: "Event Processing",
};

const ITEM_LABELS: Record<string, string> = {
    task: "Task",
    "workflow-run": "Workflow Run",
    ignored: "Ignored",
    escalated: "Escalated",
    decision: "Decision",
};

function hasItems(outcome: EventProcessingOutcome): boolean {
    return !!outcome.items && outcome.items.length > 0;
}

function taskStatusColor(status?: string): "green" | "red" | "grey" {
    return status === "Completed" ? "green" : status === "Failed" ? "red" : "grey";
}

/** Lists every result of a routing outcome, each linked to what it produced. */
function OutcomeItems({ items }: { items: EventProcessingOutcomeItem[] }) {
    return (
        <ul style={{ listStyle: "none", padding: 0, margin: "8px 0 0 0" }}>
            {items.map((item, i) => (
                <li key={i} style={{
                    display: "flex",
                    alignItems: "flex-start",
                    gap: "8px",
                    padding: "4px 0",
                    flexWrap: "wrap",
                }}>
                    {item.status === "failed" ? (
                        <TimesCircleIcon color="var(--pf-v6-global--danger-color--100)" />
                    ) : (
                        <CheckCircleIcon color="var(--pf-v6-global--success-color--100)" />
                    )}
                    <Label isCompact color={item.status === "failed" ? "red" : "grey"}>
                        {ITEM_LABELS[item.type] || item.type}: {item.status}
                    </Label>
                    {item.taskId && <span>Task #{item.taskId}</span>}
                    {item.taskId && (
                        <Label isCompact color={taskStatusColor(item.taskStatus)}>
                            {item.taskStatus || "Pending"}
                        </Label>
                    )}
                    {item.workflowRunId && (
                        <Link to={`/logs/workflow-runs/${item.workflowRunId}`}>
                            Workflow Run #{item.workflowRunId}
                        </Link>
                    )}
                    {item.projectId && (
                        <Link to={`/projects/${item.projectId}`}>
                            {item.projectName || `Project #${item.projectId}`}
                        </Link>
                    )}
                    {item.summary && <span>{item.summary}</span>}
                    {item.errorMessage && (
                        <span style={{ color: "var(--pf-v6-global--danger-color--100)" }}>
                            Error: {item.errorMessage}
                        </span>
                    )}
                </li>
            ))}
        </ul>
    );
}

function ProcessingTab({ entries, loading }: {
    entries: EventProcessingEntry[];
    loading: boolean;
}) {
    const [expandedItems, setExpandedItems] = useState<Set<number>>(new Set());

    const toggleItem = (id: number) => {
        const next = new Set(expandedItems);
        if (next.has(id)) next.delete(id); else next.add(id);
        setExpandedItems(next);
    };

    if (loading) {
        return <EmptyState><EmptyStateBody>Loading processing data...</EmptyStateBody></EmptyState>;
    }

    if (entries.length === 0) {
        return (
            <EmptyState>
                <EmptyStateBody>No subscriptions have processed this event yet.</EmptyStateBody>
            </EmptyState>
        );
    }

    return (
        <DataList aria-label="Event Processing Audit Trail" isCompact>
            {entries.map((entry) => {
                const isExpanded = expandedItems.has(entry.id);
                const hasOutcomes = entry.outcomes && entry.outcomes.length > 0;
                const hasFailed = entry.status === "failed";
                const isExpandable = hasOutcomes || hasFailed;

                return (
                    <DataListItem
                        key={entry.id}
                        id={`entry-${entry.id}`}
                        aria-labelledby={`entry-label-${entry.id}`}
                        isExpanded={isExpanded}
                    >
                        <DataListItemRow>
                            {isExpandable ? (
                                <DataListToggle
                                    id={`toggle-${entry.id}`}
                                    onClick={() => toggleItem(entry.id)}
                                    isExpanded={isExpanded}
                                    aria-controls={`content-${entry.id}`}
                                    aria-label="Toggle details"
                                />
                            ) : (
                                <div style={{ width: 48 }} />
                            )}
                            <DataListItemCells
                                dataListCells={[
                                    <DataListCell key="status" width={1}>
                                        <Label isCompact
                                            color={STATUS_COLORS[entry.status] || "grey"}>
                                            {entry.status}
                                        </Label>
                                    </DataListCell>,
                                    <DataListCell key="sub" width={2}
                                        id={`entry-label-${entry.id}`}>
                                        <Link to={`/events/subscriptions/${entry.subscriptionId}`}>
                                            {entry.subscriptionName}
                                        </Link>
                                    </DataListCell>,
                                    <DataListCell key="rules" width={2}>
                                        {entry.routingRules && entry.routingRules.length > 0
                                            ? `${entry.routingRules.length} routing rule${entry.routingRules.length > 1 ? "s" : ""}`
                                            : entry.status === "skipped" ? "---" : "No routing rules"}
                                    </DataListCell>,
                                    <DataListCell key="time" width={2}>
                                        {entry.processedOn
                                            ? new Date(entry.processedOn).toLocaleString()
                                            : "---"}
                                    </DataListCell>,
                                ]}
                            />
                        </DataListItemRow>
                        {isExpandable && (
                            <DataListContent
                                aria-label="Entry details"
                                id={`content-${entry.id}`}
                                isHidden={!isExpanded}
                                hasNoPadding={false}
                            >
                                {hasFailed && entry.errorMessage && (
                                    <div style={{
                                        padding: "8px 16px",
                                        color: "var(--pf-v6-global--danger-color--100)",
                                    }}>
                                        <strong>Error:</strong> {entry.errorMessage}
                                    </div>
                                )}
                                {hasOutcomes && (
                                    <div style={{ padding: "8px 16px" }}>
                                        {entry.outcomes!.map((o, i) => (
                                            <div key={i} style={{
                                                display: "flex",
                                                alignItems: "flex-start",
                                                gap: "12px",
                                                marginBottom: i < entry.outcomes!.length - 1 ? "12px" : 0,
                                                padding: "8px 0",
                                                borderBottom: i < entry.outcomes!.length - 1
                                                    ? "1px solid var(--pf-v6-global--BorderColor--100)"
                                                    : "none",
                                            }}>
                                                <div style={{ flexShrink: 0 }}>
                                                    <Label isCompact color="blue">
                                                        {ROUTING_LABELS[o.type] || o.type}
                                                    </Label>
                                                </div>
                                                <div style={{ flexShrink: 0, marginTop: "2px" }}>
                                                    {o.summary && !o.summary.toLowerCase().includes("failed") ? (
                                                        <CheckCircleIcon color="var(--pf-v6-global--success-color--100)" />
                                                    ) : (
                                                        <TimesCircleIcon color="var(--pf-v6-global--danger-color--100)" />
                                                    )}
                                                </div>
                                                <div style={{ flex: 1 }}>
                                                    <div>{o.summary}</div>
                                                    <div style={{
                                                        display: "flex",
                                                        gap: "12px",
                                                        marginTop: "4px",
                                                        flexWrap: "wrap",
                                                    }}>
                                                        {!hasItems(o) && o.projectId && (
                                                            <Link to={`/projects/${o.projectId}`}>
                                                                {o.projectName || `Project #${o.projectId}`}
                                                            </Link>
                                                        )}
                                                        {!hasItems(o) && o.taskId && (
                                                            <Label isCompact
                                                                color={o.taskStatus === "Completed" ? "green"
                                                                    : o.taskStatus === "Failed" ? "red"
                                                                    : "grey"}>
                                                                {o.taskStatus || "Pending"}
                                                            </Label>
                                                        )}
                                                        {o.traceId && (
                                                            <Link to={`/logs/traces/${o.traceId}`}
                                                                onClick={(e) => e.stopPropagation()}>
                                                                View Trace
                                                            </Link>
                                                        )}
                                                    </div>
                                                    {hasItems(o) && <OutcomeItems items={o.items!} />}
                                                </div>
                                            </div>
                                        ))}
                                    </div>
                                )}
                            </DataListContent>
                        )}
                    </DataListItem>
                );
            })}
        </DataList>
    );
}
