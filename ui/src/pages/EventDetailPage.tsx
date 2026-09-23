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

    return (
        <Table aria-label="Event Processing Audit Trail" variant="compact">
            <Thead>
                <Tr>
                    <Th>Status</Th>
                    <Th>Subscription</Th>
                    <Th>Created</Th>
                    <Th>Processed</Th>
                    <Th>Error</Th>
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
                        <Td style={{ whiteSpace: "nowrap" }}>
                            {entry.createdOn
                                ? new Date(entry.createdOn).toLocaleString()
                                : "---"}
                        </Td>
                        <Td style={{ whiteSpace: "nowrap" }}>
                            {entry.processedOn
                                ? new Date(entry.processedOn).toLocaleString()
                                : "---"}
                        </Td>
                        <Td>
                            {entry.errorMessage ? (
                                <Tooltip content={entry.errorMessage}>
                                    <span style={{
                                        maxWidth: "300px",
                                        display: "inline-block",
                                        overflow: "hidden",
                                        textOverflow: "ellipsis",
                                        whiteSpace: "nowrap",
                                        verticalAlign: "middle",
                                    }}>
                                        {entry.errorMessage}
                                    </span>
                                </Tooltip>
                            ) : "---"}
                        </Td>
                    </Tr>
                ))}
            </Tbody>
        </Table>
    );
}
