import { useState, useEffect, useCallback } from "react";
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
                                <Th>Time</Th>
                                <Th>Event</Th>
                                <Th>Type</Th>
                                <Th>Summary</Th>
                                <Th />
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
                                            <Label isCompact color="blue" title={entry.eventId}>
                                                {entry.eventId.substring(0, 8)}
                                            </Label>
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
                                        {(entry.entryType === "manager-evaluated"
                                                || entry.entryType === "manager-error") && (
                                            <Button variant="link" isInline
                                                onClick={() => handleViewLog(entry.id)}>
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
                activityId={logActivityId}
                onClose={() => setIsLogModalOpen(false)}
            />

        </PageSection>
    );
}
