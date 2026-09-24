import { useState, useEffect, useCallback } from "react";
import { useParams, Link } from "react-router-dom";
import {
    Breadcrumb,
    BreadcrumbItem,
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
    Tooltip,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { useEffectiveTheme } from "../hooks/useTheme";
import {
    type StreamEvent,
    type EventProcessingEntry,
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
                <BreadcrumbItem><Link to="/logs/events">Event Stream</Link></BreadcrumbItem>
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
                        <Link to={`/connections/${event.connectionId}`}>{event.connectionId}</Link>
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

function ProcessingTab({ entries, loading }: {
    entries: EventProcessingEntry[];
    loading: boolean;
}) {
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

    const ROUTING_TYPE_LABELS: Record<string, string> = {
        manager: "Send to Manager",
        "workflow-dispatch": "Dispatch to Workflows",
        "create-workflow": "Create Workflow",
        "invoke-action": "Invoke Action",
    };

    return (
        <Table aria-label="Event Processing Audit Trail" variant="compact">
            <Thead>
                <Tr>
                    <Th>Status</Th>
                    <Th>Subscription</Th>
                    <Th>Routing</Th>
                    <Th>Outcomes</Th>
                    <Th>Processed</Th>
                </Tr>
            </Thead>
            <Tbody>
                {entries.map((entry) => (
                    <Tr key={entry.id}>
                        <Td>
                            <Label isCompact
                                color={STATUS_COLORS[entry.status] || "grey"}>
                                {entry.status}
                            </Label>
                        </Td>
                        <Td>
                            <Link to={`/subscriptions/${entry.subscriptionId}`}>
                                {entry.subscriptionName}
                            </Link>
                        </Td>
                        <Td>
                            {entry.routingRules && entry.routingRules.length > 0
                                ? entry.routingRules.map((r, i) => (
                                    <div key={i}>
                                        <Label isCompact color="blue" style={{ marginBottom: "2px" }}>
                                            {ROUTING_TYPE_LABELS[r.type] || r.type}
                                        </Label>
                                    </div>
                                ))
                                : entry.status === "skipped" ? "---" : "No routing rules"}
                        </Td>
                        <Td>
                            {entry.outcomes && entry.outcomes.length > 0
                                ? entry.outcomes.map((o, i) => (
                                    <div key={i} style={{ marginBottom: "4px" }}>
                                        {o.type === "manager-evaluated" && (
                                            <span>{o.summary}</span>
                                        )}
                                        {o.type === "event-ignored" && (
                                            <span style={{ fontStyle: "italic" }}>Ignored: {o.summary}</span>
                                        )}
                                        {o.type === "manager-escalation" && (
                                            <Label isCompact color="orange">Escalated</Label>
                                        )}
                                        {(o.type === "task-created" || o.type === "project-created") && (
                                            <span>
                                                {o.projectId && (
                                                    <Link to={`/projects/${o.projectId}`}>
                                                        {o.projectName || `Project #${o.projectId}`}
                                                    </Link>
                                                )}
                                                {o.taskId && (
                                                    <span>
                                                        {" → "}
                                                        <Label isCompact
                                                            color={o.taskStatus === "Completed" ? "green"
                                                                : o.taskStatus === "Failed" ? "red"
                                                                : "grey"}>
                                                            {o.taskStatus || "Pending"}
                                                        </Label>
                                                    </span>
                                                )}
                                                {o.traceId && (
                                                    <span>
                                                        {" "}
                                                        <Link to={`/logs/traces/${o.traceId}`}
                                                            onClick={(e) => e.stopPropagation()}>
                                                            View Trace
                                                        </Link>
                                                    </span>
                                                )}
                                            </span>
                                        )}
                                    </div>
                                ))
                                : entry.status === "skipped" ? "---"
                                : entry.status === "completed" ? "No details available"
                                : entry.errorMessage ? (
                                    <Tooltip content={entry.errorMessage}>
                                        <span style={{
                                            maxWidth: "300px", display: "inline-block",
                                            overflow: "hidden", textOverflow: "ellipsis",
                                            whiteSpace: "nowrap", color: "var(--pf-v6-global--danger-color--100)",
                                        }}>
                                            {entry.errorMessage}
                                        </span>
                                    </Tooltip>
                                ) : "---"}
                        </Td>
                        <Td style={{ whiteSpace: "nowrap" }}>
                            {entry.processedOn
                                ? new Date(entry.processedOn).toLocaleString()
                                : "---"}
                        </Td>
                    </Tr>
                ))}
            </Tbody>
        </Table>
    );
}
