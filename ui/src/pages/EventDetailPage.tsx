import { useState, useEffect, useCallback } from "react";
import { useParams, Link } from "react-router-dom";
import {
    Alert,
    Breadcrumb,
    BreadcrumbItem,
    Button,
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
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import { BanIcon, CheckCircleIcon, TimesCircleIcon } from "@patternfly/react-icons";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { useEffectiveTheme } from "../hooks/useTheme";
import {
    type StreamEvent,
    type EventProcessingEntry,
    type EventProcessingOutcome,
    type EventProcessingOutcomeItem,
    fetchStreamEvent,
    fetchEventProcessing,
    retryEventProcessing,
    type WorkflowInstanceInfo,
    type WorkflowRunSummary,
    fetchWorkflowRuns,
} from "../config/api";
import { sseClient, type AxiomSseEvent } from "../config/sse";
import { LineagePanel } from "../components/LineagePanel";
import { RunWorkflowFromEventModal } from "../components/RunWorkflowFromEventModal";

const SOURCE_COLORS: Record<string, "blue" | "green" | "orange" | "grey"> = {
    github: "blue",
    jira: "green",
};

const STATUS_COLORS: Record<string, "green" | "grey" | "red" | "yellow" | "orange"> = {
    completed: "green",
    skipped: "grey",
    failed: "orange",
    exhausted: "red",
    filter_error: "red",
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
    const [isRunOpen, setIsRunOpen] = useState(false);
    const [startedRun, setStartedRun] = useState<WorkflowInstanceInfo | null>(null);
    const [runsRefresh, setRunsRefresh] = useState(0);

    const onRunStarted = (run: WorkflowInstanceInfo) => {
        setIsRunOpen(false);
        setStartedRun(run);
        setActiveTab(2);
        setRunsRefresh((n) => n + 1);
    };

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

            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between",
                marginBottom: "16px" }}>
                <Title headingLevel="h1" size="lg">
                    {event.type}
                </Title>
                <Button variant="primary" onClick={() => setIsRunOpen(true)}>Run workflow…</Button>
            </div>

            {startedRun && (
                <Alert variant="success" isInline title="Workflow run started" style={{ marginBottom: "16px" }}
                    actionClose={<Button variant="plain" aria-label="Close alert"
                        onClick={() => setStartedRun(null)}>×</Button>}>
                    <Link to={`/runs/${startedRun.runId ?? startedRun.id}`}>
                        {startedRun.title || `Workflow Run #${startedRun.runId ?? startedRun.id}`}
                    </Link>
                </Alert>
            )}

            {event.validationErrors && event.validationErrors.length > 0 && (
                <Alert variant="warning" isInline style={{ marginBottom: "16px" }}
                    title={`This event does not match the ${event.type} schema`}>
                    <p>
                        Axiom stored the event anyway. Subscription filters that use the fields named in the
                        errors below may not behave as expected.
                    </p>
                    <ul>
                        {event.validationErrors.map((error, index) => (
                            <li key={index}><code>{error}</code></li>
                        ))}
                    </ul>
                </Alert>
            )}

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
                    <DescriptionListTerm>Event Source</DescriptionListTerm>
                    <DescriptionListDescription>
                        <Link to={`/events/sources/${event.eventSourceId}`}>{event.eventSourceId}</Link>
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
                            eventId={event.id}
                            entries={processing}
                            loading={processingLoading}
                            onRetried={loadProcessing}
                        />
                    </TabContent>
                </Tab>
                <Tab eventKey={2} title={<TabTitleText>Runs</TabTitleText>}>
                    <TabContent id="runs-tab" eventKey={2} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        {activeTab === 2 && <EventRunsTab eventId={event.id} refreshKey={runsRefresh} />}
                    </TabContent>
                </Tab>
                <Tab eventKey={3} title={<TabTitleText>Lineage</TabTitleText>}>
                    <TabContent id="lineage-tab" eventKey={3} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        {activeTab === 3 && <LineagePanel entityType="event" id={event.id} expandable={false} />}
                    </TabContent>
                </Tab>
            </Tabs>

            <RunWorkflowFromEventModal eventId={event.id} isOpen={isRunOpen}
                onClose={() => setIsRunOpen(false)} onStarted={onRunStarted} />
        </PageSection>
    );
}

const RUN_STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    running: "blue",
    waiting: "orange",
    completed: "green",
    failed: "red",
    cancelled: "grey",
};

/** Lists the workflow runs started from an event; refreshes on workflow-updated SSE events. */
function EventRunsTab({ eventId, refreshKey }: { eventId: string; refreshKey: number }) {
    const [runs, setRuns] = useState<WorkflowRunSummary[]>([]);
    const [loading, setLoading] = useState(true);

    const load = useCallback(() => {
        fetchWorkflowRuns(1, 100, { triggerEventId: eventId })
            .then((r) => setRuns(r.items))
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [eventId]);

    useEffect(() => { load(); }, [load, refreshKey]);

    useEffect(() => {
        let timeout: ReturnType<typeof setTimeout>;
        const unsubscribe = sseClient.subscribe((e: AxiomSseEvent) => {
            if (e.type === "workflow-updated") {
                clearTimeout(timeout);
                timeout = setTimeout(load, 300);
            }
        });
        return () => { clearTimeout(timeout); unsubscribe(); };
    }, [load]);

    if (loading) {
        return <EmptyState><EmptyStateBody>Loading runs...</EmptyStateBody></EmptyState>;
    }
    if (runs.length === 0) {
        return <EmptyState><EmptyStateBody>No runs were started from this event.</EmptyStateBody></EmptyState>;
    }
    return (
        <Table aria-label="Runs started from this event" variant="compact">
            <Thead>
                <Tr>
                    <Th>Status</Th>
                    <Th>Title</Th>
                    <Th>Workflow</Th>
                    <Th>Started</Th>
                </Tr>
            </Thead>
            <Tbody>
                {runs.map((r) => (
                    <Tr key={r.runId}>
                        <Td dataLabel="Status">
                            <Label isCompact color={RUN_STATUS_COLORS[r.status] || "grey"}>{r.status}</Label>
                        </Td>
                        <Td dataLabel="Title">
                            <Link to={`/runs/${r.runId}`}>{r.title || `Workflow Run #${r.runId}`}</Link>
                            {r.labels?.includes("trigger:manual") && (
                                <Label isCompact color="purple" style={{ marginLeft: "8px" }}>manual</Label>
                            )}
                        </Td>
                        <Td dataLabel="Workflow">{r.definitionName ?? `#${r.definitionId}`}</Td>
                        <Td dataLabel="Started">{new Date(r.startedOn).toLocaleString()}</Td>
                    </Tr>
                ))}
            </Tbody>
        </Table>
    );
}

const ROUTING_LABELS: Record<string, string> = {
    "workflow-dispatch": "Dispatch to Workflows",
    "create-workflow": "Create Workflow",
    processing: "Event Processing",
};

const ITEM_LABELS: Record<string, string> = {
    "workflow-run": "Workflow Run",
    "workflow-resumed": "Resumed Workflow Run",
    "no-match": "No Match",
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
                    ) : item.status === "skipped" ? (
                        <BanIcon color="var(--pf-v6-global--Color--200)" />
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
                        <Link to={`/runs/${item.workflowRunId}`}>
                            {item.type === "workflow-resumed" ? "Resumed" : "Started"}{" "}
                            Workflow Run #{item.workflowRunId}
                        </Link>
                    )}
                    {item.workflowRunId && item.traceId && (
                        <Link to={`/logs/traces/${item.traceId}`}>View Trace</Link>
                    )}
                    {item.subjectId && (
                        <Link to={`/subjects/${item.subjectId}`}>
                            {item.subjectName || `Subject #${item.subjectId}`}
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

/** Whether outcome {@code i} is the first one of its attempt (outcomes are ordered by attempt). */
function startsAttempt(outcomes: EventProcessingOutcome[], i: number): boolean {
    const attempt = outcomes[i].attemptNumber;
    return attempt != null && (i === 0 || outcomes[i - 1].attemptNumber !== attempt);
}

/** Attempt count, last attempt and next retry (or "gave up") of a processing entry. */
function AttemptSummary({ entry, retrying, onRetry }: {
    entry: EventProcessingEntry;
    retrying: boolean;
    onRetry: () => void;
}) {
    const canRetry = entry.status === "failed" || entry.status === "exhausted"
        || entry.status === "filter_error";
    let next = "---";
    if (entry.status === "exhausted") next = "Gave up";
    else if (entry.status === "filter_error") next = "Not retried automatically (fix the filter)";
    else if (entry.nextAttemptAt) next = new Date(entry.nextAttemptAt).toLocaleString();
    return (
        <div style={{ display: "flex", gap: "24px", alignItems: "center", flexWrap: "wrap",
            padding: "8px 16px" }}>
            <span>
                <strong>Attempts:</strong> {entry.attemptCount ?? 0}
                {entry.maxAttempts != null && ` of ${entry.maxAttempts}`}
            </span>
            <span>
                <strong>Last attempt:</strong>{" "}
                {entry.lastAttemptAt ? new Date(entry.lastAttemptAt).toLocaleString() : "---"}
            </span>
            {canRetry && <span><strong>Next retry:</strong> {next}</span>}
            {canRetry && (
                <Button variant="secondary" size="sm" isLoading={retrying} isDisabled={retrying}
                    onClick={onRetry}>
                    {entry.status === "exhausted" ? "Retry once more" : "Retry now"}
                </Button>
            )}
        </div>
    );
}

function ProcessingTab({ eventId, entries, loading, onRetried }: {
    eventId: string;
    entries: EventProcessingEntry[];
    loading: boolean;
    onRetried: () => void;
}) {
    const [expandedItems, setExpandedItems] = useState<Set<number>>(new Set());
    const [retryingId, setRetryingId] = useState<number | null>(null);
    const [retryError, setRetryError] = useState<string | null>(null);

    const retry = (ledgerId: number) => {
        setRetryingId(ledgerId);
        setRetryError(null);
        retryEventProcessing(eventId, ledgerId)
            .then(onRetried)
            .catch((err: unknown) => setRetryError(err instanceof Error ? err.message : String(err)))
            .finally(() => setRetryingId(null));
    };

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
        <>
        {retryError && <Alert variant="danger" isInline title={retryError} style={{ marginBottom: "16px" }} />}
        <DataList aria-label="Event Processing Audit Trail" isCompact>
            {entries.map((entry) => {
                const isExpanded = expandedItems.has(entry.id);
                const hasOutcomes = entry.outcomes && entry.outcomes.length > 0;
                const hasFailed = entry.status === "failed" || entry.status === "exhausted"
                    || entry.status === "filter_error";
                const hasAttempts = (entry.attemptCount ?? 0) > 0;
                const isExpandable = hasOutcomes || hasFailed || hasAttempts;

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
                                            {entry.status === "filter_error" ? "filter error" : entry.status}
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
                                {hasAttempts && (
                                    <AttemptSummary entry={entry} retrying={retryingId === entry.id}
                                        onRetry={() => retry(entry.id)} />
                                )}
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
                                                flexWrap: "wrap",
                                                marginBottom: i < entry.outcomes!.length - 1 ? "12px" : 0,
                                                padding: "8px 0",
                                                borderBottom: i < entry.outcomes!.length - 1
                                                    ? "1px solid var(--pf-v6-global--BorderColor--100)"
                                                    : "none",
                                            }}>
                                                {startsAttempt(entry.outcomes!, i) && (
                                                    <div style={{ flexBasis: "100%" }}>
                                                        <strong>Attempt {o.attemptNumber}</strong>
                                                    </div>
                                                )}
                                                <div style={{ flexShrink: 0 }}>
                                                    <Label isCompact color="blue">
                                                        {ROUTING_LABELS[o.type] || o.type}
                                                    </Label>
                                                </div>
                                                <div style={{ flexShrink: 0, marginTop: "2px" }}>
                                                    {o.status !== "failed" && o.summary && !o.summary.toLowerCase().includes("failed") ? (
                                                        <CheckCircleIcon color="var(--pf-v6-global--success-color--100)" />
                                                    ) : (
                                                        <TimesCircleIcon color="var(--pf-v6-global--danger-color--100)" />
                                                    )}
                                                </div>
                                                <div style={{ flex: 1 }}>
                                                    <div>{o.summary}</div>
                                                    {o.errorMessage && (
                                                        <div style={{ color: "var(--pf-v6-global--danger-color--100)" }}>
                                                            Error: {o.errorMessage}
                                                        </div>
                                                    )}
                                                    <div style={{
                                                        display: "flex",
                                                        gap: "12px",
                                                        marginTop: "4px",
                                                        flexWrap: "wrap",
                                                    }}>
                                                        {!hasItems(o) && o.subjectId && (
                                                            <Link to={`/subjects/${o.subjectId}`}>
                                                                {o.subjectName || `Subject #${o.subjectId}`}
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
        </>
    );
}
