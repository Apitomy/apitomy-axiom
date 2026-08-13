import { useState, useEffect, useCallback } from "react";
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
import { ColoredLabel } from "../components/ColoredLabel";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import {
    type ChipFilterCriteria,
    type ChipFilterType,
    ChipFilterInput,
    FilterChips,
} from "@apitomy/common-ui-components";
import { type AxiomEvent, fetchEvents } from "../config/api";
import { EventDetailModal } from "../components/EventDetailModal";

const SOURCE_COLORS: Record<string, "blue" | "green" | "orange" | "grey"> = {
    github: "blue",
    jira: "green",
    internal: "orange",
};

const FILTER_STATUS_COLORS: Record<string, "green" | "red" | "grey"> = {
    allowed: "green",
    blocked: "red",
};

const FILTER_TYPES: ChipFilterType[] = [
    { value: "source", label: "Source", testId: "event-filter-source" },
    { value: "eventType", label: "Event Type", testId: "event-filter-eventType" },
    { value: "repository", label: "Repository", testId: "event-filter-repository" },
    { value: "labels", label: "Labels", testId: "event-filter-labels" },
    { value: "filterStatus", label: "Filter Status", testId: "event-filter-filterStatus" },
];

export function EventsPage() {
    const navigate = useNavigate();
    const [events, setEvents] = useState<AxiomEvent[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [loading, setLoading] = useState(true);

    const [filters, setFilters] = useState<ChipFilterCriteria[]>([]);

    // Payload modal
    const [selectedEvent, setSelectedEvent] = useState<AxiomEvent | null>(null);

    const filterSource = filters.find((f) => f.filterBy.value === "source")?.filterValue;
    const filterEventType = filters.find((f) => f.filterBy.value === "eventType")?.filterValue;
    const filterRepository = filters.find((f) => f.filterBy.value === "repository")?.filterValue;
    const filterLabels = filters
        .filter((f) => f.filterBy.value === "labels")
        .map((f) => f.filterValue)
        .join(",");
    const filterFilterStatus = filters.find((f) => f.filterBy.value === "filterStatus")?.filterValue;
    const isFiltered = filters.length > 0;

    const loadData = useCallback(() => {
        setLoading(true);
        fetchEvents(
            page, perPage,
            filterSource || undefined,
            filterEventType || undefined,
            filterRepository || undefined,
            filterLabels || undefined,
            filterFilterStatus || undefined
        )
            .then((results) => {
                setEvents(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterSource, filterEventType, filterRepository, filterLabels, filterFilterStatus]);

    useEffect(() => { loadData(); }, [loadData]);

    const onAddFilterCriteria = (criteria: ChipFilterCriteria) => {
        if (!criteria.filterValue) return;
        const deduplicated = filters.filter((f) =>
            !(f.filterBy.value === criteria.filterBy.value && f.filterValue === criteria.filterValue));
        if (criteria.filterBy.value === "labels") {
            deduplicated.push(criteria);
            setFilters(deduplicated);
        } else {
            const withoutSame = deduplicated.filter((f) => f.filterBy.value !== criteria.filterBy.value);
            withoutSame.push(criteria);
            setFilters(withoutSame);
        }
        setPage(1);
    };

    const addLabelFilter = (label: string) => {
        const already = filters.some((f) => f.filterBy.value === "labels" && f.filterValue === label);
        if (!already) {
            const labelType = FILTER_TYPES.find((t) => t.value === "labels")!;
            setFilters([...filters, { filterBy: labelType, filterValue: label }]);
            setPage(1);
        }
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
                Events
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
                                : "No events recorded yet."}
                        </EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Events" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>#</Th>
                                <Th>Time</Th>
                                <Th>Source</Th>
                                <Th>Filter</Th>
                                <Th>Labels</Th>
                                <Th>Event Type</Th>
                                <Th>Repository</Th>
                                <Th>Issue</Th>
                                <Th>Project</Th>
                                <Th>Trace</Th>
                            </Tr>
                        </Thead>
                        <Tbody>
                            {events.map((event) => (
                                <Tr key={event.id} isClickable
                                    onRowClick={() => setSelectedEvent(event)}>
                                    <Td>{event.id}</Td>
                                    <Td style={{ whiteSpace: "nowrap" }}>
                                        {new Date(event.receivedAt).toLocaleString()}
                                    </Td>
                                    <Td>
                                        <Label isCompact
                                            color={SOURCE_COLORS[event.source] || "grey"}
                                            style={{ cursor: "pointer" }}
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                const sourceType = FILTER_TYPES.find((t) => t.value === "source")!;
                                                onAddFilterCriteria({ filterBy: sourceType, filterValue: event.source });
                                            }}>
                                            {event.source}
                                        </Label>
                                    </Td>
                                    <Td>
                                        {event.filterStatus ? (
                                            <Label isCompact
                                                color={FILTER_STATUS_COLORS[event.filterStatus] || "grey"}
                                                style={{ cursor: "pointer" }}
                                                onClick={(e) => {
                                                    e.stopPropagation();
                                                    const statusType = FILTER_TYPES.find((t) => t.value === "filterStatus")!;
                                                    onAddFilterCriteria({ filterBy: statusType, filterValue: event.filterStatus! });
                                                }}>
                                                {event.filterStatus}
                                            </Label>
                                        ) : (
                                            <Label isCompact color="grey">—</Label>
                                        )}
                                    </Td>
                                    <Td>
                                        {event.labels && event.labels.length > 0
                                            ? event.labels.map((lbl) => (
                                                <ColoredLabel key={lbl} isCompact
                                                    style={{ marginRight: "4px", cursor: "pointer" }}
                                                    onClick={(e) => {
                                                        e.stopPropagation();
                                                        addLabelFilter(lbl);
                                                    }}>
                                                    {lbl}
                                                </ColoredLabel>
                                            ))
                                            : "—"}
                                    </Td>
                                    <Td>{event.eventType}</Td>
                                    <Td>{event.repository || "—"}</Td>
                                    <Td>{event.issueRef || "—"}</Td>
                                    <Td>
                                        {event.projectId
                                            ? `Project #${event.projectId}`
                                            : "—"}
                                    </Td>
                                    <Td>
                                        {event.traceId ? (
                                            <Button variant="link" isInline
                                                onClick={(e) => {
                                                    e.stopPropagation();
                                                    navigate(`/logs/traces/${event.traceId}`);
                                                }}>
                                                View Trace
                                            </Button>
                                        ) : "—"}
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

            <EventDetailModal
                event={selectedEvent}
                onClose={() => setSelectedEvent(null)}
            />
        </PageSection>
    );
}
