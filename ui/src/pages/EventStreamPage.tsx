import { useState, useEffect, useCallback, useRef } from "react";
import { useNavigate } from "react-router-dom";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Label,
    PageSection,
    Pagination,
    Title,
    Tooltip,
    Toolbar,
    ToolbarContent,
    ToolbarItem,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";
import InProgressIcon from "@patternfly/react-icons/dist/esm/icons/in-progress-icon";
import {
    type ChipFilterCriteria,
    type ChipFilterType,
    ChipFilterInput,
    FilterChips,
} from "@apitomy/common-ui-components";
import {
    type StreamEvent,
    fetchStreamEvents,
} from "../config/api";
import { sseClient, type AxiomSseEvent } from "../config/sse";

const SOURCE_COLORS: Record<string, "blue" | "green" | "orange" | "grey"> = {
    github: "blue",
    jira: "green",
};

const FILTER_TYPES: ChipFilterType[] = [
    { value: "type", label: "Event Type", testId: "stream-filter-type" },
    { value: "eventSourceId", label: "Event Source", testId: "stream-filter-event-source" },
    { value: "ref", label: "Ref", testId: "stream-filter-ref" },
    { value: "actor", label: "Actor", testId: "stream-filter-actor" },
];

/**
 * Extracts a readable short label from a full ref URL.
 *
 * - GitHub issue/PR URLs: "owner/repo#123"
 * - Jira browse URLs: "PROJ-123"
 * - Fallback: last ~50 characters with ellipsis
 */
function formatRef(ref?: string): string {
    if (!ref) return "—";
    try {
        const url = new URL(ref);

        // GitHub: /owner/repo/issues/123 or /owner/repo/pull/123
        const ghMatch = url.pathname.match(/^\/([^/]+\/[^/]+)\/(?:issues|pull)\/(\d+)/);
        if (ghMatch) return `${ghMatch[1]}#${ghMatch[2]}`;

        // Jira: /browse/PROJ-123
        const jiraMatch = url.pathname.match(/\/browse\/([A-Z][A-Z0-9_]+-\d+)/);
        if (jiraMatch) return jiraMatch[1];
    } catch {
        // Not a valid URL; fall through to truncation.
    }

    if (ref.length > 50) return `...${ref.slice(-47)}`;
    return ref;
}

export function EventStreamPage() {
    const navigate = useNavigate();
    const [events, setEvents] = useState<StreamEvent[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [loading, setLoading] = useState(true);

    const [filters, setFilters] = useState<ChipFilterCriteria[]>([]);

    const filterType = filters.find((f) => f.filterBy.value === "type")?.filterValue;
    const filterEventSourceId = filters.find((f) => f.filterBy.value === "eventSourceId")?.filterValue;
    const filterRef = filters.find((f) => f.filterBy.value === "ref")?.filterValue;
    const filterActor = filters.find((f) => f.filterBy.value === "actor")?.filterValue;
    const isFiltered = filters.length > 0;

    const loadData = useCallback(() => {
        setLoading(true);
        fetchStreamEvents(
            page, perPage,
            filterType || undefined,
            filterEventSourceId || undefined,
            filterRef || undefined,
            filterActor || undefined
        )
            .then((results) => {
                setEvents(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterType, filterEventSourceId, filterRef, filterActor]);

    useEffect(() => { loadData(); }, [loadData]);

    // Auto-refresh when new stream events arrive via SSE (debounced)
    const refreshTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
    useEffect(() => {
        const unsubscribe = sseClient.subscribe((event: AxiomSseEvent) => {
            if (event.type === "stream-event") {
                // Debounce: wait 1s after last SSE event before refreshing
                if (refreshTimer.current) clearTimeout(refreshTimer.current);
                refreshTimer.current = setTimeout(() => loadData(), 1000);
            }
        });
        return () => {
            unsubscribe();
            if (refreshTimer.current) clearTimeout(refreshTimer.current);
        };
    }, [loadData]);

    const onAddFilterCriteria = (criteria: ChipFilterCriteria) => {
        if (!criteria.filterValue) return;
        const withoutSame = filters.filter((f) => f.filterBy.value !== criteria.filterBy.value);
        withoutSame.push(criteria);
        setFilters(withoutSame);
        setPage(1);
    };

    const onRemoveFilterCriteria = (criteria: ChipFilterCriteria) => {
        setFilters(filters.filter((f) =>
            !(f.filterBy.value === criteria.filterBy.value && f.filterValue === criteria.filterValue)));
        setPage(1);
    };

    const onClearAllFilters = () => {
        setFilters([]);
        setPage(1);
    };

    return (
        <PageSection>
            <Title headingLevel="h1" size="lg" style={{ marginBottom: "16px" }}>
                Event Stream
            </Title>

            <Toolbar>
                <ToolbarContent>
                    <ToolbarItem>
                        <ChipFilterInput
                            filterTypes={FILTER_TYPES}
                            onAddCriteria={onAddFilterCriteria} />
                    </ToolbarItem>
                    <ToolbarItem>
                        <Button variant="control" aria-label="Refresh" onClick={loadData}>
                            <SyncAltIcon />
                        </Button>
                    </ToolbarItem>
                    <ToolbarItem variant="pagination" align={{ default: "alignEnd" }}>
                        <Pagination
                            itemCount={totalCount}
                            page={page}
                            perPage={perPage}
                            onSetPage={(_e, p) => setPage(p)}
                            onPerPageSelect={(_e, pp) => { setPerPage(pp); setPage(1); }}
                            isCompact
                        />
                    </ToolbarItem>
                </ToolbarContent>
            </Toolbar>
            {isFiltered && (
                <Toolbar>
                    <ToolbarContent>
                        <ToolbarItem>
                            <FilterChips
                                criteria={filters}
                                onClearAllCriteria={onClearAllFilters}
                                onRemoveCriteria={onRemoveFilterCriteria} />
                        </ToolbarItem>
                    </ToolbarContent>
                </Toolbar>
            )}

            <div>
                {loading ? (
                    <EmptyState>
                        <EmptyStateBody>Loading events...</EmptyStateBody>
                    </EmptyState>
                ) : events.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>
                            {isFiltered
                                ? "No events match the current filters."
                                : "No events in the stream."}
                        </EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Event Stream" variant="compact">
                        <Thead>
                            <Tr>
                                <Th style={{ width: "40px" }}></Th>
                                <Th>Time</Th>
                                <Th>Source</Th>
                                <Th>Event Source</Th>
                                <Th>Event Type</Th>
                                <Th>Ref</Th>
                                <Th>Actor</Th>
                            </Tr>
                        </Thead>
                        <Tbody>
                            {events.map((event) => (
                                <Tr key={event.id} isClickable
                                    onRowClick={() => navigate(`/events/stream/${event.id}`)}>
                                    <Td>
                                        <MatchIndicator event={event} />
                                    </Td>
                                    <Td style={{ whiteSpace: "nowrap" }}>
                                        {new Date(event.timestamp).toLocaleString()}
                                    </Td>
                                    <Td>
                                        <Label isCompact
                                            color={SOURCE_COLORS[event.source] || "grey"}
                                            style={{ cursor: "pointer" }}
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                const typeFilter = FILTER_TYPES.find((t) => t.value === "eventSourceId")!;
                                                onAddFilterCriteria({ filterBy: typeFilter, filterValue: event.eventSourceId });
                                            }}>
                                            {event.source}
                                        </Label>
                                    </Td>
                                    <Td>{event.eventSourceId}</Td>
                                    <Td>
                                        <Label isCompact
                                            style={{ cursor: "pointer" }}
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                const typeFilter = FILTER_TYPES.find((t) => t.value === "type")!;
                                                onAddFilterCriteria({ filterBy: typeFilter, filterValue: event.type });
                                            }}>
                                            {event.type}
                                        </Label>
                                    </Td>
                                    <Td title={event.ref}>
                                        {formatRef(event.ref)}
                                    </Td>
                                    <Td>
                                        {event.actor?.login ? (
                                            <Label isCompact variant="outline" onClick={(e) => {
                                                e.stopPropagation();
                                                const actorFilter = FILTER_TYPES.find((t) => t.value === "actor")!;
                                                onAddFilterCriteria({ filterBy: actorFilter,
                                                    filterValue: event.actor?.login as string });
                                            }}>
                                                {event.actor.login as string}
                                            </Label>
                                        ) : ((event.actor?.displayName as string) || "—")}
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

        </PageSection>
    );
}

/** Formats a count with a singular or plural noun. */
function plural(count: number, singular: string, pluralForm: string): string {
    return `${count} ${count === 1 ? singular : pluralForm}`;
}

/**
 * Summarises how many subscriptions matched an event and how their processing went: red when any
 * matched subscription failed or any filter errored, an in-progress icon while any is pending, green
 * when all completed. Nothing is shown when no subscription matched and no filter errored.
 */
export function MatchIndicator({ event }: { event: StreamEvent }) {
    const matched = event.matchedSubscriptions ?? 0;
    const states = event.processingStates;
    const filterErrors = states?.filterError ?? 0;
    if (matched <= 0 && filterErrors <= 0) {
        return null;
    }
    const failedMatches = (states?.failed ?? 0) + (states?.exhausted ?? 0);
    const failures = failedMatches + filterErrors;
    const pending = states?.pending ?? 0;
    const completed = states?.completed ?? 0;
    const phrases: string[] = [];
    if (matched > 0) {
        const parts: string[] = [];
        if (completed > 0) parts.push(`${completed} completed`);
        if (pending > 0) parts.push(`${pending} pending`);
        if (failedMatches > 0) parts.push(`${failedMatches} failed`);
        const head = `Matched ${plural(matched, "subscription", "subscriptions")}`;
        phrases.push(parts.length > 0 ? `${head}: ${parts.join(", ")}` : head);
    }
    if (filterErrors > 0) {
        phrases.push(plural(filterErrors, "filter error", "filter errors"));
    }
    const label = phrases.join("; ");
    let state: "danger" | "in-progress" | "success";
    let icon;
    if (failures > 0) {
        state = "danger";
        icon = <ExclamationCircleIcon aria-hidden="true"
            color="var(--pf-t--global--icon--color--status--danger--default)" />;
    } else if (pending > 0) {
        state = "in-progress";
        icon = <InProgressIcon aria-hidden="true"
            color="var(--pf-t--global--icon--color--status--info--default)" />;
    } else {
        state = "success";
        icon = <CheckCircleIcon aria-hidden="true"
            color="var(--pf-t--global--icon--color--status--success--default)" />;
    }
    return (
        <Tooltip content={label}>
            <span role="img" aria-label={label} data-state={state} tabIndex={0}>{icon}</span>
        </Tooltip>
    );
}

