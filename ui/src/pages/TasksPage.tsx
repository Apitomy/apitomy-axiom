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
import BanIcon from "@patternfly/react-icons/dist/esm/icons/ban-icon";
import {
    type ChipFilterCriteria,
    type ChipFilterType,
    ChipFilterInput,
    FilterChips,
} from "@apitomy/common-ui-components";
import { type Task, fetchAllTasks, cancelTask } from "../config/api";
import { ExecutionLogModal } from "../components/ExecutionLogModal";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";

const STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    Pending: "blue",
    InProgress: "green",
    AwaitingInput: "orange",
    Completed: "grey",
    Failed: "red",
    Cancelled: "grey",
};

const FILTER_TYPES: ChipFilterType[] = [
    { value: "actionType", label: "Action Type", testId: "task-filter-actionType" },
    { value: "status", label: "Status", testId: "task-filter-status" },
];

export function TasksPage() {
    const [tasks, setTasks] = useState<Task[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [loading, setLoading] = useState(true);

    const [filters, setFilters] = useState<ChipFilterCriteria[]>([]);

    // Execution log modal
    const [isLogModalOpen, setIsLogModalOpen] = useState(false);
    const [logProjectId, setLogProjectId] = useState<number | null>(null);
    const [logTaskId, setLogTaskId] = useState<number | null>(null);

    // Cancel confirmation modal
    const [cancelTarget, setCancelTarget] = useState<Task | null>(null);

    const filterActionType = filters.find((f) => f.filterBy.value === "actionType")?.filterValue;
    const filterStatus = filters
        .filter((f) => f.filterBy.value === "status")
        .map((f) => f.filterValue)
        .join(",");
    const isFiltered = filters.length > 0;

    const loadData = useCallback(() => {
        setLoading(true);
        fetchAllTasks(
            page, perPage,
            filterActionType || undefined,
            filterStatus || undefined
        )
            .then((results) => {
                setTasks(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterActionType, filterStatus]);

    useEffect(() => { loadData(); }, [loadData]);

    const onAddFilterCriteria = (criteria: ChipFilterCriteria) => {
        if (!criteria.filterValue) return;
        const updated = filters.filter((f) =>
            !(f.filterBy.value === criteria.filterBy.value && f.filterValue === criteria.filterValue));
        if (criteria.filterBy.value === "actionType") {
            // Single-value filter: replace existing
            const withoutSame = updated.filter((f) => f.filterBy.value !== criteria.filterBy.value);
            withoutSame.push(criteria);
            setFilters(withoutSame);
        } else {
            // Multi-value filter (status): accumulate
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

    const handleViewLog = (projectId: number, taskId: number) => {
        setLogProjectId(projectId);
        setLogTaskId(taskId);
        setIsLogModalOpen(true);
    };

    const handleCancel = (task: Task) => {
        setCancelTarget(task);
    };

    const confirmCancel = () => {
        if (cancelTarget) {
            cancelTask(cancelTarget.projectId, cancelTarget.id)
                .then(loadData)
                .catch(console.error);
            setCancelTarget(null);
        }
    };

    const isActive = (status: string) =>
        status === "InProgress" || status === "AwaitingInput" || status === "Pending";

    return (
        <PageSection>
            <Title headingLevel="h1" size="lg" style={{ marginBottom: "16px" }}>
                Tasks
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
                        <EmptyStateBody>Loading tasks...</EmptyStateBody>
                    </EmptyState>
                ) : tasks.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>
                            {isFiltered
                                ? "No tasks match the current filters."
                                : "No tasks recorded yet."}
                        </EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Tasks" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>#</Th>
                                <Th>Action Type</Th>
                                <Th>Project</Th>
                                <Th>Status</Th>
                                <Th>Created By</Th>
                                <Th>Created</Th>
                                <Th>Completed</Th>
                                <Th />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {tasks.map((task) => (
                                <Tr key={task.id}>
                                    <Td>{task.id}</Td>
                                    <Td>{task.actionType}</Td>
                                    <Td>
                                        <Link to={`/projects/${task.projectId}`}>
                                            Project #{task.projectId}
                                        </Link>
                                    </Td>
                                    <Td>
                                        <Label isCompact color={STATUS_COLORS[task.status] || "grey"}
                                            style={{ cursor: "pointer" }}
                                            onClick={() => {
                                                const already = filters.some((f) =>
                                                    f.filterBy.value === "status" && f.filterValue === task.status);
                                                if (!already) {
                                                    const statusType = FILTER_TYPES.find((t) => t.value === "status")!;
                                                    setFilters([...filters, { filterBy: statusType, filterValue: task.status }]);
                                                    setPage(1);
                                                }
                                            }}>
                                            {task.status}
                                        </Label>
                                    </Td>
                                    <Td>{task.createdBy}</Td>
                                    <Td style={{ whiteSpace: "nowrap" }}>
                                        {new Date(task.createdOn).toLocaleString()}
                                    </Td>
                                    <Td style={{ whiteSpace: "nowrap" }}>
                                        {task.completedOn
                                            ? new Date(task.completedOn).toLocaleString()
                                            : "—"}
                                    </Td>
                                    <Td>
                                        {(task.status === "Completed" || task.status === "Failed") && (
                                            <Button variant="link" isInline
                                                onClick={() => handleViewLog(task.projectId, task.id)}>
                                                View Log
                                            </Button>
                                        )}
                                        {isActive(task.status) && (
                                            <Button variant="link" isInline isDanger
                                                icon={<BanIcon />}
                                                onClick={() => handleCancel(task)}>
                                                Cancel
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
                projectId={logProjectId}
                taskId={logTaskId}
                onClose={() => setIsLogModalOpen(false)}
            />

            <ConfirmDeleteModal isOpen={cancelTarget !== null} title="Cancel Task"
                onConfirm={confirmCancel} onCancel={() => setCancelTarget(null)}
                confirmLabel="Cancel Task">
                {cancelTarget && `Cancel task #${cancelTarget.id} (${cancelTarget.actionType})?`}
            </ConfirmDeleteModal>
        </PageSection>
    );
}
