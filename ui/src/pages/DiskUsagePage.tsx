import { useState, useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import {
    Button,
    Card,
    CardBody,
    EmptyState,
    EmptyStateBody,
    Gallery,
    GalleryItem,
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
import { type DiskUsageSubject, fetchDiskUsage, formatBytes } from "../config/api";
import { useListUrlState } from "../hooks/useListUrlState";
import type { ListStateConfig } from "../utils/listUrlState";

const FILTER_TYPES: ChipFilterType[] = [
    { value: "name", label: "Subject Name", testId: "disk-filter-name" },
];

const LIST_CONFIG: ListStateConfig = { filterTypes: FILTER_TYPES };

const SORT_KEYS: readonly ("name" | "diskUsageBytes")[] = ["name", "diskUsageBytes"];

export function DiskUsagePage() {
    const navigate = useNavigate();
    const [subjects, setSubjects] = useState<DiskUsageSubject[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [totalDiskUsageBytes, setTotalDiskUsageBytes] = useState(0);
    const [subjectCount, setSubjectCount] = useState(0);
    const [loading, setLoading] = useState(true);

    const { filters, page, perPage, setFilters, setPage, setPerPage, getParam, setParams } =
        useListUrlState(LIST_CONFIG);
    const sortParam: string | null = getParam("sort");
    const activeSortIndex: number = Math.max(0, SORT_KEYS.indexOf(sortParam as "name" | "diskUsageBytes"));
    const activeSortDirection: "asc" | "desc" = getParam("dir") === "desc" ? "desc" : "asc";

    const filterName = filters.find((f) => f.filterBy.value === "name")?.filterValue;
    const isFiltered = filters.length > 0;

    const sortBy = SORT_KEYS[activeSortIndex];

    const loadData = useCallback(() => {
        setLoading(true);
        fetchDiskUsage(page, perPage, filterName || undefined, sortBy, activeSortDirection)
            .then((results) => {
                setSubjects(results.items);
                setTotalCount(results.totalCount);
                setTotalDiskUsageBytes(results.totalDiskUsageBytes || 0);
                setSubjectCount(results.subjectCount || 0);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterName, sortBy, activeSortDirection]);

    useEffect(() => { loadData(); }, [loadData]);

    const onAddFilterCriteria = (criteria: ChipFilterCriteria) => {
        if (!criteria.filterValue) return;
        const withoutSame = filters.filter((f) => f.filterBy.value !== criteria.filterBy.value);
        withoutSame.push(criteria);
        setFilters(withoutSame);
    };

    const onRemoveFilterCriteria = (criteria: ChipFilterCriteria) => {
        setFilters(filters.filter((f) =>
            !(f.filterBy.value === criteria.filterBy.value && f.filterValue === criteria.filterValue)));
    };

    const onClearAllFilters = () => {
        setFilters([]);
    };

    const onSort = (_event: React.MouseEvent, columnIndex: number, direction: "asc" | "desc") => {
        setParams({
            sort: columnIndex === 0 ? undefined : SORT_KEYS[columnIndex],
            dir: direction === "asc" ? undefined : direction,
        }, { resetPage: true });
    };

    return (
        <PageSection>
            <Title headingLevel="h1" size="lg" style={{ marginBottom: "16px" }}>
                Disk Usage
            </Title>

            <Gallery hasGutter minWidths={{ default: "180px" }} style={{ marginBottom: "16px" }}>
                <GalleryItem>
                    <Card isCompact>
                        <CardBody style={{ textAlign: "center", padding: "16px" }}>
                            <div style={{ fontSize: "24px", fontWeight: "bold" }}>
                                {formatBytes(totalDiskUsageBytes)}
                            </div>
                            <div className="axiom-text-subtle" style={{ fontSize: "13px" }}>
                                Total Disk Usage
                            </div>
                        </CardBody>
                    </Card>
                </GalleryItem>
                <GalleryItem>
                    <Card isCompact>
                        <CardBody style={{ textAlign: "center", padding: "16px" }}>
                            <div style={{ fontSize: "24px", fontWeight: "bold" }}>
                                {subjectCount}
                            </div>
                            <div className="axiom-text-subtle" style={{ fontSize: "13px" }}>
                                Subjects
                            </div>
                        </CardBody>
                    </Card>
                </GalleryItem>
            </Gallery>

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
                            onPerPageSelect={(_e, pp) => setPerPage(pp)}
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

            {loading ? (
                <EmptyState>
                    <EmptyStateBody>Loading disk usage...</EmptyStateBody>
                </EmptyState>
            ) : subjects.length === 0 ? (
                <EmptyState>
                    <EmptyStateBody>
                        {isFiltered
                            ? "No subjects match the current filter."
                            : "No subjects found."}
                    </EmptyStateBody>
                </EmptyState>
            ) : (
                <Table aria-label="Disk Usage by Subject" variant="compact">
                    <Thead>
                        <Tr>
                            <Th sort={{
                                sortBy: { index: activeSortIndex, direction: activeSortDirection },
                                onSort,
                                columnIndex: 0,
                            }}>Subject</Th>
                            <Th sort={{
                                sortBy: { index: activeSortIndex, direction: activeSortDirection },
                                onSort,
                                columnIndex: 1,
                            }}>Disk Usage</Th>
                        </Tr>
                    </Thead>
                    <Tbody>
                        {subjects.map((p) => (
                            <Tr key={p.subjectId} isClickable
                                onRowClick={() => navigate(`/subjects/${p.subjectId}`)}>
                                <Td>{p.subjectName}</Td>
                                <Td>{formatBytes(p.diskUsageBytes)}</Td>
                            </Tr>
                        ))}
                    </Tbody>
                </Table>
            )}
        </PageSection>
    );
}
