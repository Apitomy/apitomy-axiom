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
    Toolbar,
    ToolbarContent,
    ToolbarItem,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
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
    { value: "connectionId", label: "Connection", testId: "stream-filter-connection" },
    { value: "ref", label: "Ref", testId: "stream-filter-ref" },
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
    const filterConnectionId = filters.find((f) => f.filterBy.value === "connectionId")?.filterValue;
    const filterRef = filters.find((f) => f.filterBy.value === "ref")?.filterValue;
    const isFiltered = filters.length > 0;

    const loadData = useCallback(() => {
        setLoading(true);
        fetchStreamEvents(
            page, perPage,
            filterType || undefined,
            filterConnectionId || undefined,
            filterRef || undefined
        )
            .then((results) => {
                setEvents(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterType, filterConnectionId, filterRef]);

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
                                <Th>Time</Th>
                                <Th>Source</Th>
                                <Th>Connection</Th>
                                <Th>Event Type</Th>
                                <Th>Ref</Th>
                                <Th>Actor</Th>
                            </Tr>
                        </Thead>
                        <Tbody>
                            {events.map((event) => (
                                <Tr key={event.id} isClickable
                                    onRowClick={() => navigate(`/logs/events/${event.id}`)}>
                                    <Td style={{ whiteSpace: "nowrap" }}>
                                        {new Date(event.timestamp).toLocaleString()}
                                    </Td>
                                    <Td>
                                        <Label isCompact
                                            color={SOURCE_COLORS[event.source] || "grey"}
                                            style={{ cursor: "pointer" }}
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                const typeFilter = FILTER_TYPES.find((t) => t.value === "connectionId")!;
                                                onAddFilterCriteria({ filterBy: typeFilter, filterValue: event.connectionId });
                                            }}>
                                            {event.source}
                                        </Label>
                                    </Td>
                                    <Td>{event.connectionId}</Td>
                                    <Td>
                                        <Label isCompact>{event.type}</Label>
                                    </Td>
                                    <Td title={event.ref}>
                                        {formatRef(event.ref)}
                                    </Td>
                                    <Td>
                                        {(event.actor?.login as string)
                                            || (event.actor?.displayName as string)
                                            || "—"}
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
