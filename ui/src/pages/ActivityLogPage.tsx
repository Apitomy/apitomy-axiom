import { useState, useEffect, useCallback } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
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
import { type ActivityLogEntry, fetchActivityLog } from "../config/api";
import { ExecutionLogModal } from "../components/ExecutionLogModal";

const ENTRY_TYPE_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    "event-received": "blue",
    "task-created": "green",
    "task-started": "green",
    "task-completed": "green",
    "task-failed": "red",
    "project-created": "blue",
    "project-closed": "grey",
    "project-reopened": "orange",
    "event-ignored": "grey",
    "manager-evaluated": "blue",
    "manager-escalation": "orange",
    "manager-no-decision": "grey",
    "manager-skipped": "grey",
    "manager-error": "red",
    "pipeline-error": "red",
    "task-awaiting-input": "orange",
    "report-generating": "blue",
    "report-completed": "green",
    "report-failed": "red",
};

const FILTER_TYPES: ChipFilterType[] = [
    { value: "eventId", label: "Event ID", testId: "activity-filter-eventId" },
    { value: "traceId", label: "Trace ID", testId: "activity-filter-traceId" },
    { value: "entryType", label: "Entry Type", testId: "activity-filter-entryType" },
    { value: "summary", label: "Summary", testId: "activity-filter-summary" },
    { value: "projectId", label: "Project ID", testId: "activity-filter-projectId" },
];

export function ActivityLogPage() {
    const navigate = useNavigate();
    const [entries, setEntries] = useState<ActivityLogEntry[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [loading, setLoading] = useState(true);

    const [searchParams] = useSearchParams();
    const [filters, setFilters] = useState<ChipFilterCriteria[]>(() => {
        const initialTraceId = searchParams.get("traceId");
        return initialTraceId
            ? [{ filterBy: FILTER_TYPES.find((t) => t.value === "traceId")!, filterValue: initialTraceId }]
            : [];
    });

    // Execution log modal state
    const [isLogModalOpen, setIsLogModalOpen] = useState(false);
    const [logProjectId, setLogProjectId] = useState<number | null>(null);
    const [logTaskId, setLogTaskId] = useState<number | null>(null);
    const [logActivityId, setLogActivityId] = useState<number | null>(null);

    const handleViewTaskLog = (projectId: number, taskId: number) => {
        setLogProjectId(projectId);
        setLogTaskId(taskId);
        setLogActivityId(null);
        setIsLogModalOpen(true);
    };

    const handleViewActivityLog = (activityId: number) => {
        setLogProjectId(null);
        setLogTaskId(null);
        setLogActivityId(activityId);
        setIsLogModalOpen(true);
    };

    const filterEventId = filters.find((f) => f.filterBy.value === "eventId")?.filterValue;
    const filterTraceId = filters.find((f) => f.filterBy.value === "traceId")?.filterValue;
    const filterSummary = filters.find((f) => f.filterBy.value === "summary")?.filterValue;
    const filterProjectId = filters.find((f) => f.filterBy.value === "projectId")?.filterValue;
    const filterEntryTypes = filters
        .filter((f) => f.filterBy.value === "entryType")
        .map((f) => f.filterValue)
        .join(",");
    const isFiltered = filters.length > 0;

    const loadData = useCallback(() => {
        setLoading(true);
        fetchActivityLog(
            page, perPage,
            filterEventId?.trim() || undefined,
            filterSummary || undefined,
            filterProjectId ? Number(filterProjectId) : undefined,
            filterEntryTypes || undefined,
            undefined,
            filterTraceId?.trim() || undefined
        )
            .then((results) => {
                setEntries(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterEventId, filterSummary, filterProjectId, filterEntryTypes, filterTraceId]);

    useEffect(() => {
        loadData();
    }, [loadData]);

    const onAddFilterCriteria = (criteria: ChipFilterCriteria) => {
        if (!criteria.filterValue) return;
        const updated = filters.filter((f) =>
            !(f.filterBy.value === criteria.filterBy.value && f.filterValue === criteria.filterValue));
        if (criteria.filterBy.value === "eventId" || criteria.filterBy.value === "traceId"
                || criteria.filterBy.value === "summary"
                || criteria.filterBy.value === "projectId") {
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
            <Title headingLevel="h1" size="lg">All Activity</Title>

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
                        <EmptyStateBody>Loading activity log...</EmptyStateBody>
                    </EmptyState>
                ) : entries.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>
                            {isFiltered
                                ? "No entries match the current filters."
                                : "No activity yet."}
                        </EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Activity Log" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Time</Th>
                                <Th>Event</Th>
                                <Th>Trace</Th>
                                <Th>Type</Th>
                                <Th>Summary</Th>
                                <Th>Project</Th>
                            </Tr>
                        </Thead>
                        <Tbody>
                            {entries.map((entry) => (
                                <Tr key={entry.id}>
                                    <Td style={{ whiteSpace: "nowrap" }}>
                                        {new Date(entry.createdOn).toLocaleString()}
                                    </Td>
                                    <Td>
                                        {entry.eventId ? (
                                            <Label isCompact color="blue"
                                                style={{ cursor: "pointer" }}
                                                title={entry.eventId}
                                                onClick={() => {
                                                    const eventIdType = FILTER_TYPES.find((t) => t.value === "eventId")!;
                                                    onAddFilterCriteria({ filterBy: eventIdType, filterValue: entry.eventId! });
                                                }}>
                                                {entry.eventId.substring(0, 8)}
                                            </Label>
                                        ) : "—"}
                                    </Td>
                                    <Td>
                                        {entry.traceId ? (
                                            <Link to={`/logs/traces/${entry.traceId}`} title={entry.traceId}>
                                                {entry.traceId.substring(0, 8)}
                                            </Link>
                                        ) : "—"}
                                    </Td>
                                    <Td>
                                        <Label
                                            isCompact
                                            color={ENTRY_TYPE_COLORS[entry.entryType] || "grey"}
                                            style={{ cursor: "pointer" }}
                                            onClick={() => {
                                                const entryTypeType = FILTER_TYPES.find((t) => t.value === "entryType")!;
                                                const already = filters.some((f) =>
                                                    f.filterBy.value === "entryType" && f.filterValue === entry.entryType);
                                                if (!already) {
                                                    setFilters([...filters, { filterBy: entryTypeType, filterValue: entry.entryType }]);
                                                    setPage(1);
                                                }
                                            }}>
                                            {entry.entryType}
                                        </Label>
                                    </Td>
                                    <Td>
                                        {entry.summary}
                                        {(entry.entryType === "task-completed" || entry.entryType === "task-failed")
                                                && entry.projectId && entry.taskId && (
                                            <>
                                                {" — "}
                                                <Button variant="link" isInline
                                                    onClick={() => handleViewTaskLog(entry.projectId!, entry.taskId!)}>
                                                    View Log
                                                </Button>
                                            </>
                                        )}
                                        {(entry.entryType === "manager-evaluated" || entry.entryType === "manager-error") && (
                                            <>
                                                {" — "}
                                                <Button variant="link" isInline
                                                    onClick={() => handleViewActivityLog(entry.id)}>
                                                    View Log
                                                </Button>
                                            </>
                                        )}
                                    </Td>
                                    <Td>
                                        {entry.projectId ? (
                                            <Button
                                                variant="link"
                                                isInline
                                                onClick={() =>
                                                    navigate(`/projects/${entry.projectId}`)
                                                }
                                            >
                                                Project #{entry.projectId}
                                            </Button>
                                        ) : (
                                            "—"
                                        )}
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

            <ExecutionLogModal
                isOpen={isLogModalOpen}
                projectId={logProjectId}
                taskId={logTaskId}
                activityId={logActivityId}
                onClose={() => setIsLogModalOpen(false)}
            />
        </PageSection>
    );
}
