import { useState, useEffect, useCallback } from "react";
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
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import {
    type ChipFilterCriteria,
    type ChipFilterType,
    ChipFilterInput,
    FilterChips,
} from "@apitomy/common-ui-components";
import { type ScheduledJobRun, fetchAllScheduledJobRuns } from "../config/api";
import { ExecutionLogModal } from "../components/ExecutionLogModal";

const STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    Pending: "grey",
    Running: "blue",
    Completed: "green",
    Failed: "red",
};

const FILTER_TYPES: ChipFilterType[] = [
    { value: "jobName", label: "Job Name", testId: "run-filter-jobName" },
    { value: "status", label: "Status", testId: "run-filter-status" },
    { value: "trigger", label: "Trigger", testId: "run-filter-trigger" },
];

function formatDuration(ms: number): string {
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
    const mins = Math.floor(ms / 60_000);
    const secs = Math.round((ms % 60_000) / 1000);
    return `${mins}m ${secs}s`;
}

function formatCost(cost?: number): string {
    return cost != null ? `$${cost.toFixed(4)}` : "—";
}

export function ScheduledJobRunsPage() {
    const [runs, setRuns] = useState<ScheduledJobRun[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [loading, setLoading] = useState(true);

    const [filters, setFilters] = useState<ChipFilterCriteria[]>([]);

    const [isLogModalOpen, setIsLogModalOpen] = useState(false);
    const [logRunId, setLogRunId] = useState<number | null>(null);

    const filterJobName = filters.find((f) => f.filterBy.value === "jobName")?.filterValue;
    const filterStatus = filters
        .filter((f) => f.filterBy.value === "status")
        .map((f) => f.filterValue)
        .join(",");
    const filterTrigger = filters.find((f) => f.filterBy.value === "trigger")?.filterValue;
    const isFiltered = filters.length > 0;

    const loadData = useCallback(() => {
        setLoading(true);
        fetchAllScheduledJobRuns(
            page, perPage,
            filterJobName || undefined,
            filterStatus || undefined,
            filterTrigger || undefined
        )
            .then((results) => {
                setRuns(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterJobName, filterStatus, filterTrigger]);

    useEffect(() => { loadData(); }, [loadData]);

    const onAddFilterCriteria = (criteria: ChipFilterCriteria) => {
        if (!criteria.filterValue) return;
        const updated = filters.filter((f) =>
            !(f.filterBy.value === criteria.filterBy.value
                && f.filterValue === criteria.filterValue));
        if (criteria.filterBy.value === "status") {
            updated.push(criteria);
            setFilters(updated);
        } else {
            const withoutSame = updated.filter(
                (f) => f.filterBy.value !== criteria.filterBy.value);
            withoutSame.push(criteria);
            setFilters(withoutSame);
        }
        setPage(1);
    };

    const onRemoveFilterCriteria = (criteria: ChipFilterCriteria) => {
        setFilters(filters.filter((f) =>
            !(f.filterBy.value === criteria.filterBy.value
                && f.filterValue === criteria.filterValue)));
        setPage(1);
    };

    const onClearAllFilters = () => {
        setFilters([]);
        setPage(1);
    };

    const handleViewLog = (runId: number) => {
        setLogRunId(runId);
        setIsLogModalOpen(true);
    };

    return (
        <PageSection>
            <Title headingLevel="h1" size="lg" style={{ marginBottom: "16px" }}>
                Job Runs
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
                        <EmptyStateBody>Loading job runs...</EmptyStateBody>
                    </EmptyState>
                ) : runs.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>
                            {isFiltered
                                ? "No job runs match the current filters."
                                : "No scheduled job runs recorded yet."}
                        </EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Job Runs" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Status</Th>
                                <Th>Job</Th>
                                <Th>Trigger</Th>
                                <Th>Started</Th>
                                <Th>Duration</Th>
                                <Th>Cost</Th>
                                <Th>Actions</Th>
                            </Tr>
                        </Thead>
                        <Tbody>
                            {runs.map((run) => (
                                <Tr key={run.id}>
                                    <Td>
                                        <Label isCompact
                                            color={STATUS_COLORS[run.status] || "grey"}
                                            style={{ cursor: "pointer" }}
                                            onClick={() => {
                                                const already = filters.some((f) =>
                                                    f.filterBy.value === "status"
                                                    && f.filterValue === run.status);
                                                if (!already) {
                                                    const statusType = FILTER_TYPES.find(
                                                        (t) => t.value === "status")!;
                                                    setFilters([...filters, {
                                                        filterBy: statusType,
                                                        filterValue: run.status,
                                                    }]);
                                                    setPage(1);
                                                }
                                            }}>
                                            {run.status}
                                        </Label>
                                    </Td>
                                    <Td>
                                        <Link to={`/scheduled-jobs/${run.jobId}`}>
                                            {run.jobName || `Job #${run.jobId}`}
                                        </Link>
                                    </Td>
                                    <Td>{run.trigger}</Td>
                                    <Td style={{ whiteSpace: "nowrap" }}>
                                        {run.startedAt
                                            ? new Date(run.startedAt).toLocaleString()
                                            : "—"}
                                    </Td>
                                    <Td style={{ whiteSpace: "nowrap" }}>
                                        {run.durationMs != null
                                            ? formatDuration(run.durationMs) : "—"}
                                    </Td>
                                    <Td>{formatCost(run.costUsd)}</Td>
                                    <Td>
                                        {run.traceId && (
                                            <Link to={`/logs/traces/${run.traceId}`}>
                                                View Trace
                                            </Link>
                                        )}
                                        {run.traceId && run.executionLog
                                            && (run.status === "Completed"
                                                || run.status === "Failed") && " | "}
                                        {(run.status === "Completed" || run.status === "Failed")
                                            && run.executionLog && (
                                            <Button variant="link" isInline
                                                onClick={() => handleViewLog(run.id)}>
                                                View Log
                                            </Button>
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
                scheduledJobRunId={logRunId}
                onClose={() => setIsLogModalOpen(false)}
            />
        </PageSection>
    );
}
