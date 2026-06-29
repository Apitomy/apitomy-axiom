import { useState, useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    EmptyStateFooter,
    EmptyStateActions,
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
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import { ColoredLabel } from "../components/ColoredLabel";
import {
    type ChipFilterCriteria,
    type ChipFilterType,
    ChipFilterInput,
    FilterChips,
} from "@apitomy/common-ui-components";
import { type Report, fetchReports, deleteReport } from "../config/api";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";

const STATUS_COLORS: Record<string, "blue" | "green" | "grey" | "red"> = {
    Pending: "blue",
    Generating: "blue",
    Completed: "green",
    Failed: "red",
};

const FILTER_TYPES: ChipFilterType[] = [
    { value: "title", label: "Title", testId: "report-filter-title" },
    { value: "status", label: "Status", testId: "report-filter-status" },
    { value: "labels", label: "Labels", testId: "report-filter-labels" },
];

export function ReportsPage() {
    const navigate = useNavigate();
    const [reports, setReports] = useState<Report[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [loading, setLoading] = useState(true);

    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);

    const [filters, setFilters] = useState<ChipFilterCriteria[]>([]);

    const filterTitle = filters.find((f) => f.filterBy.value === "title")?.filterValue;
    const filterStatus = filters
        .filter((f) => f.filterBy.value === "status")
        .map((f) => f.filterValue)
        .join(",");
    const filterLabels = filters
        .filter((f) => f.filterBy.value === "labels")
        .map((f) => f.filterValue)
        .join(",");
    const isFiltered = filters.length > 0;

    const loadData = useCallback(() => {
        setLoading(true);
        fetchReports(
            page, perPage,
            undefined,
            filterStatus || undefined,
            filterTitle || undefined,
            filterLabels || undefined
        )
            .then((results) => {
                setReports(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterTitle, filterStatus, filterLabels]);

    useEffect(() => { loadData(); }, [loadData]);

    const handleDelete = (e: React.MouseEvent, id: number) => {
        e.stopPropagation();
        setDeleteTarget(id);
    };

    const confirmDelete = () => {
        if (deleteTarget !== null) {
            deleteReport(deleteTarget).then(loadData).catch(console.error);
            setDeleteTarget(null);
        }
    };

    const onAddFilterCriteria = (criteria: ChipFilterCriteria) => {
        if (!criteria.filterValue) return;
        const updated = filters.filter((f) =>
            !(f.filterBy.value === criteria.filterBy.value && f.filterValue === criteria.filterValue));
        if (criteria.filterBy.value === "title") {
            const withoutTitle = updated.filter((f) => f.filterBy.value !== "title");
            withoutTitle.push(criteria);
            setFilters(withoutTitle);
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
            <Title headingLevel="h1" size="lg">Reports</Title>

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
                        <EmptyStateBody>Loading reports...</EmptyStateBody>
                    </EmptyState>
                ) : reports.length === 0 && isFiltered ? (
                    <EmptyState>
                        <EmptyStateBody>
                            No reports match the current filters.
                        </EmptyStateBody>
                    </EmptyState>
                ) : reports.length === 0 ? (
                    <EmptyState headingLevel="h2" titleText="No reports yet">
                        <EmptyStateBody>
                            Reports are generated from Report Definitions. Configure and
                            enable a report definition, or click "Run Now" on an existing
                            one to generate a report.
                        </EmptyStateBody>
                        <EmptyStateFooter>
                            <EmptyStateActions>
                                <Button variant="primary"
                                    onClick={() => navigate("/report-definitions")}>
                                    Go to Report Definitions
                                </Button>
                            </EmptyStateActions>
                        </EmptyStateFooter>
                    </EmptyState>
                ) : (
                    <Table aria-label="Reports" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Title</Th>
                                <Th>Status</Th>
                                <Th>Time Range</Th>
                                <Th>Labels</Th>
                                <Th>Generated</Th>
                                <Th />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {reports.map((report) => (
                                <Tr key={report.id} isClickable
                                    onRowClick={() => navigate(`/reports/${report.id}`)}>
                                    <Td>{report.title || `Report #${report.id}`}</Td>
                                    <Td>
                                        <Label isCompact
                                            color={STATUS_COLORS[report.status] || "grey"}
                                            style={{ cursor: "pointer" }}
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                const already = filters.some((f) =>
                                                    f.filterBy.value === "status" && f.filterValue === report.status);
                                                if (!already) {
                                                    const statusType = FILTER_TYPES.find((t) => t.value === "status")!;
                                                    setFilters([...filters, { filterBy: statusType, filterValue: report.status }]);
                                                    setPage(1);
                                                }
                                            }}>
                                            {report.status}
                                        </Label>
                                    </Td>
                                    <Td>
                                        {report.timeRangeStart && report.timeRangeEnd
                                            ? `${new Date(report.timeRangeStart).toLocaleDateString()} \u2014 ${new Date(report.timeRangeEnd).toLocaleDateString()}`
                                            : "\u2014"}
                                    </Td>
                                    <Td>
                                        {(report.labels || []).map((label) => (
                                            <ColoredLabel key={label} isCompact
                                                style={{ marginRight: "4px", cursor: "pointer" }}
                                                onClick={(e) => {
                                                    e.stopPropagation();
                                                    const already = filters.some((f) =>
                                                        f.filterBy.value === "labels" && f.filterValue === label);
                                                    if (!already) {
                                                        const labelsType = FILTER_TYPES.find((t) => t.value === "labels")!;
                                                        setFilters([...filters, { filterBy: labelsType, filterValue: label }]);
                                                        setPage(1);
                                                    }
                                                }}>
                                                {label}
                                            </ColoredLabel>
                                        ))}
                                    </Td>
                                    <Td style={{ whiteSpace: "nowrap" }}>
                                        {new Date(report.createdOn).toLocaleString()}
                                    </Td>
                                    <Td>
                                        <Button variant="plain" size="sm" style={{ padding: 0 }}
                                            onClick={(e) => handleDelete(e, report.id)}>
                                            <TrashIcon />
                                        </Button>
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

            <ConfirmDeleteModal isOpen={deleteTarget !== null} title="Delete Report"
                onConfirm={confirmDelete} onCancel={() => setDeleteTarget(null)}>
                Are you sure you want to delete this report? This action cannot be undone.
            </ConfirmDeleteModal>
        </PageSection>
    );
}
