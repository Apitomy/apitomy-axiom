import { Fragment, useState, useEffect, useCallback } from "react";
import { Link } from "react-router-dom";
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
import { ExpandableRowContent, Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import {
    type ChipFilterCriteria,
    type ChipFilterType,
    ChipFilterInput,
    FilterChips,
} from "@apitomy/common-ui-components";
import {
    type ActivityLogEntry,
    type TraceDetail,
    fetchActivityLog,
    fetchTraceDetail,
} from "../config/api";
import { STATUS_COLORS } from "../components/TraceGraphNode";
import { ExecutionLogModal } from "../components/ExecutionLogModal";

const MANAGER_ENTRY_TYPES = "manager-evaluated,manager-error,manager-skipped,manager-escalation,manager-no-decision";

const ENTRY_TYPE_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    "manager-evaluated": "blue",
    "manager-error": "red",
    "manager-skipped": "grey",
    "manager-escalation": "orange",
    "manager-no-decision": "grey",
};

const FILTER_TYPES: ChipFilterType[] = [
    { value: "summary", label: "Summary", testId: "manager-filter-summary" },
    { value: "projectId", label: "Project ID", testId: "manager-filter-projectId" },
];

export function ManagerDecisionsPage() {
    const [entries, setEntries] = useState<ActivityLogEntry[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [loading, setLoading] = useState(true);

    const [filters, setFilters] = useState<ChipFilterCriteria[]>([]);

    // Log modal
    const [isLogModalOpen, setIsLogModalOpen] = useState(false);
    const [logActivityId, setLogActivityId] = useState<number | null>(null);

    const filterSummary = filters.find((f) => f.filterBy.value === "summary")?.filterValue;
    const filterProjectId = filters.find((f) => f.filterBy.value === "projectId")?.filterValue;
    const isFiltered = filters.length > 0;

    const loadData = useCallback(() => {
        setLoading(true);
        fetchActivityLog(
            page, perPage,
            undefined,
            filterSummary || undefined,
            filterProjectId ? Number(filterProjectId) : undefined,
            MANAGER_ENTRY_TYPES
        )
            .then((results) => {
                setEntries(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterSummary, filterProjectId]);

    useEffect(() => { loadData(); }, [loadData]);

    const onAddFilterCriteria = (criteria: ChipFilterCriteria) => {
        if (!criteria.filterValue) return;
        const updated = filters.filter((f) =>
            !(f.filterBy.value === criteria.filterBy.value && f.filterValue === criteria.filterValue));
        // All filter types on this page are single-value
        const withoutSame = updated.filter((f) => f.filterBy.value !== criteria.filterBy.value);
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

    // Rows (activity IDs) whose trace decisions are expanded
    const [expanded, setExpanded] = useState<Set<number>>(new Set());
    const toggleExpanded = (id: number) => {
        setExpanded((prev) => {
            const next = new Set(prev);
            if (next.has(id)) next.delete(id); else next.add(id);
            return next;
        });
    };

    const handleViewLog = (activityId: number) => {
        setLogActivityId(activityId);
        setIsLogModalOpen(true);
    };

    return (
        <PageSection>
            <Title headingLevel="h1" size="lg" style={{ marginBottom: "16px" }}>
                Manager Decisions
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
                        <EmptyStateBody>Loading manager decisions...</EmptyStateBody>
                    </EmptyState>
                ) : entries.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>
                            {isFiltered
                                ? "No decisions match the current filters."
                                : "No manager evaluations yet. Decisions will appear here as the Manager processes incoming events."}
                        </EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Manager Decisions" variant="compact">
                        <Thead>
                            <Tr>
                                <Th screenReaderText="Show decisions" />
                                <Th>Time</Th>
                                <Th>Event</Th>
                                <Th>Type</Th>
                                <Th>Summary</Th>
                                <Th>Trace</Th>
                                <Th />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {entries.map((entry, rowIndex) => {
                                const canExpand = entry.entryType === "manager-evaluated"
                                    && !!entry.traceId;
                                const isExpanded = expanded.has(entry.id);
                                return (
                                    <Fragment key={entry.id}>
                                        <Tr>
                                            <Td expand={canExpand ? {
                                                rowIndex,
                                                isExpanded,
                                                onToggle: () => toggleExpanded(entry.id),
                                            } : undefined} />
                                            <Td style={{ whiteSpace: "nowrap" }}>
                                                {new Date(entry.createdOn).toLocaleString()}
                                            </Td>
                                            <Td>
                                                {entry.eventId ? (
                                                    <Link to={`/events/stream/${entry.eventId}`}
                                                        title={entry.eventId}>
                                                        <Label isCompact color="blue">
                                                            {entry.eventId.substring(0, 8)}
                                                        </Label>
                                                    </Link>
                                                ) : "—"}
                                            </Td>
                                            <Td>
                                                <Label isCompact
                                                    color={ENTRY_TYPE_COLORS[entry.entryType] || "grey"}>
                                                    {entry.entryType}
                                                </Label>
                                            </Td>
                                            <Td>
                                                {entry.summary && entry.summary.length > 120
                                                    ? entry.summary.substring(0, 117) + "..."
                                                    : entry.summary}
                                            </Td>
                                            <Td>
                                                {entry.traceId ? (
                                                    <Link to={`/logs/traces/${entry.traceId}`}
                                                        title={entry.traceId}
                                                        data-testid={`manager-trace-link-${entry.id}`}>
                                                        {entry.traceId.substring(0, 8)}
                                                    </Link>
                                                ) : "—"}
                                            </Td>
                                            <Td>
                                                {(entry.entryType === "manager-evaluated"
                                                        || entry.entryType === "manager-error") && (
                                                    <Button variant="link" isInline
                                                        onClick={() => handleViewLog(entry.id)}>
                                                        View Log
                                                    </Button>
                                                )}
                                            </Td>
                                        </Tr>
                                        {canExpand && (
                                            <Tr isExpanded={isExpanded}>
                                                <Td colSpan={7}>
                                                    <ExpandableRowContent>
                                                        {isExpanded && entry.traceId && (
                                                            <ManagerTraceDecisions
                                                                traceId={entry.traceId} />
                                                        )}
                                                    </ExpandableRowContent>
                                                </Td>
                                            </Tr>
                                        )}
                                    </Fragment>
                                );
                            })}
                        </Tbody>
                    </Table>
                )}
            </div>

            <ExecutionLogModal
                isOpen={isLogModalOpen}
                activityId={logActivityId}
                onClose={() => setIsLogModalOpen(false)}
            />

        </PageSection>
    );
}

/**
 * Shows the decisions recorded in a Manager evaluation trace (one `manager-decision` node per
 * decision, holding its reasoning) and the tasks each decision created.
 */
function ManagerTraceDecisions({ traceId }: { traceId: string }) {
    const [detail, setDetail] = useState<TraceDetail | null>(null);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        fetchTraceDetail(traceId)
            .then(setDetail)
            .catch((e: Error) => setError(e.message));
    }, [traceId]);

    if (error) return <div>Failed to load trace: {error}</div>;
    if (!detail) return <div>Loading decisions...</div>;

    const decisions = detail.nodes.filter((n) => n.nodeType === "manager-decision");
    if (decisions.length === 0) {
        return <div>No decisions recorded in this trace.</div>;
    }
    return (
        <ul style={{ listStyle: "none", paddingLeft: 0, margin: 0 }}>
            {decisions.map((d) => {
                const tasks = detail.nodes.filter(
                    (n) => n.parentNodeId === d.id && n.nodeType === "task");
                return (
                    <li key={d.id} style={{ marginBottom: "8px" }}
                        data-testid={`manager-decision-${d.id}`}>
                        <Label isCompact color={STATUS_COLORS[d.status] || "grey"}>
                            {d.status}
                        </Label>{" "}
                        {d.summary}
                        {tasks.map((t) => (
                            <div key={t.id} style={{ marginLeft: "24px" }}>
                                <Label isCompact color={STATUS_COLORS[t.status] || "grey"}>
                                    {t.status}
                                </Label>{" "}
                                {t.summary}
                                {t.entityId ? ` (task #${t.entityId})` : ""}
                                {detail.trace.projectId && (
                                    <>
                                        {" — "}
                                        <Link to={`/projects/${detail.trace.projectId}`}>
                                            project #{detail.trace.projectId}
                                        </Link>
                                    </>
                                )}
                            </div>
                        ))}
                    </li>
                );
            })}
        </ul>
    );
}
