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
import { type DiskUsageProject, fetchDiskUsage, formatBytes } from "../config/api";

const FILTER_TYPES: ChipFilterType[] = [
    { value: "name", label: "Project Name", testId: "disk-filter-name" },
];

export function DiskUsagePage() {
    const navigate = useNavigate();
    const [projects, setProjects] = useState<DiskUsageProject[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [totalDiskUsageBytes, setTotalDiskUsageBytes] = useState(0);
    const [projectCount, setProjectCount] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [loading, setLoading] = useState(true);

    const [filters, setFilters] = useState<ChipFilterCriteria[]>([]);

    const filterName = filters.find((f) => f.filterBy.value === "name")?.filterValue;
    const isFiltered = filters.length > 0;

    const loadData = useCallback(() => {
        setLoading(true);
        fetchDiskUsage(page, perPage, filterName || undefined)
            .then((results) => {
                setProjects(results.items);
                setTotalCount(results.totalCount);
                setTotalDiskUsageBytes(results.totalDiskUsageBytes || 0);
                setProjectCount(results.projectCount || 0);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterName]);

    useEffect(() => { loadData(); }, [loadData]);

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
                                {projectCount}
                            </div>
                            <div className="axiom-text-subtle" style={{ fontSize: "13px" }}>
                                Projects
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

            {loading ? (
                <EmptyState>
                    <EmptyStateBody>Loading disk usage...</EmptyStateBody>
                </EmptyState>
            ) : projects.length === 0 ? (
                <EmptyState>
                    <EmptyStateBody>
                        {isFiltered
                            ? "No projects match the current filter."
                            : "No projects found."}
                    </EmptyStateBody>
                </EmptyState>
            ) : (
                <Table aria-label="Disk Usage by Project" variant="compact">
                    <Thead>
                        <Tr>
                            <Th>Project</Th>
                            <Th>Disk Usage</Th>
                        </Tr>
                    </Thead>
                    <Tbody>
                        {projects.map((p) => (
                            <Tr key={p.projectId} isClickable
                                onRowClick={() => navigate(`/projects/${p.projectId}`)}>
                                <Td>{p.projectName}</Td>
                                <Td>{formatBytes(p.diskUsageBytes)}</Td>
                            </Tr>
                        ))}
                    </Tbody>
                </Table>
            )}
        </PageSection>
    );
}
