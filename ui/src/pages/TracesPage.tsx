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
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import {
    type ChipFilterCriteria,
    type ChipFilterType,
    ChipFilterInput,
    FilterChips,
} from "@apitomy/common-ui-components";
import { type Trace, fetchTraces } from "../config/api";
import { STATUS_COLORS, formatDuration } from "../components/TraceGraphNode";

const FILTER_TYPES: ChipFilterType[] = [
    { value: "traceType", label: "Type", testId: "trace-filter-type" },
    { value: "status", label: "Status", testId: "trace-filter-status" },
];

export function TracesPage() {
    const navigate = useNavigate();
    const [traces, setTraces] = useState<Trace[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [loading, setLoading] = useState(true);
    const [filters, setFilters] = useState<ChipFilterCriteria[]>([]);

    const filterTraceType = filters.find((f) => f.filterBy.value === "traceType")?.filterValue;
    const filterStatuses = filters
        .filter((f) => f.filterBy.value === "status")
        .map((f) => f.filterValue)
        .join(",");
    const isFiltered = filters.length > 0;

    const loadData = useCallback(() => {
        setLoading(true);
        fetchTraces(
            page, perPage,
            filterTraceType || undefined,
            filterStatuses || undefined
        )
            .then((results) => {
                setTraces(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterTraceType, filterStatuses]);

    useEffect(() => { loadData(); }, [loadData]);

    const onAddFilterCriteria = (criteria: ChipFilterCriteria) => {
        if (!criteria.filterValue) return;
        const updated = filters.filter((f) =>
            !(f.filterBy.value === criteria.filterBy.value && f.filterValue === criteria.filterValue));
        if (criteria.filterBy.value === "traceType") {
            const withoutSame = updated.filter((f) => f.filterBy.value !== criteria.filterBy.value);
            withoutSame.push(criteria);
            setFilters(withoutSame);
        } else {
            updated.push(criteria);
            setFilters(updated);
        }
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
            <Title headingLevel="h1" size="lg">Traces</Title>

            <Toolbar style={{ marginTop: "16px" }}>
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
                        <EmptyStateBody>Loading traces...</EmptyStateBody>
                    </EmptyState>
                ) : traces.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>
                            {isFiltered
                                ? "No traces match the current filters."
                                : "No traces yet."}
                        </EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Traces" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>ID</Th>
                                <Th>Type</Th>
                                <Th>Status</Th>
                                <Th>Summary</Th>
                                <Th>Started</Th>
                                <Th>Duration</Th>
                            </Tr>
                        </Thead>
                        <Tbody>
                            {traces.map((trace) => (
                                <Tr key={trace.traceId}
                                    isClickable
                                    onRowClick={() => navigate(`/logs/traces/${trace.traceId}`)}>
                                    <Td style={{ fontFamily: "monospace", fontSize: "12px" }}>
                                        {trace.traceId.substring(0, 8)}...
                                    </Td>
                                    <Td>
                                        <Label isCompact>{trace.traceType}</Label>
                                    </Td>
                                    <Td>
                                        <Label isCompact
                                            color={STATUS_COLORS[trace.status]}>
                                            {trace.status}
                                        </Label>
                                    </Td>
                                    <Td>{trace.summary}</Td>
                                    <Td style={{ whiteSpace: "nowrap" }}>
                                        {new Date(trace.startedOn).toLocaleString()}
                                    </Td>
                                    <Td>
                                        {trace.completedOn && trace.startedOn
                                            ? formatDuration(
                                                new Date(trace.completedOn).getTime()
                                                    - new Date(trace.startedOn).getTime())
                                            : "—"}
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
