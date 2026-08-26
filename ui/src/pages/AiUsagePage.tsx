import { useState, useEffect, useCallback } from "react";
import {
    Button,
    Card,
    CardBody,
    EmptyState,
    EmptyStateBody,
    Gallery,
    GalleryItem,
    Label,
    PageSection,
    Pagination,
    TextInput,
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
import { type AiUsage, fetchUsage } from "../config/api";

const TYPE_COLORS: Record<string, "blue" | "green"> = {
    task: "green",
    manager: "blue",
};

const FILTER_TYPES: ChipFilterType[] = [
    { value: "invocationType", label: "Type", testId: "usage-filter-type" },
    { value: "actionType", label: "Action Type", testId: "usage-filter-action" },
    { value: "engine", label: "Engine", testId: "usage-filter-engine" },
    { value: "model", label: "Model", testId: "usage-filter-model" },
];

export function AiUsagePage() {
    const [records, setRecords] = useState<AiUsage[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [loading, setLoading] = useState(true);

    const [filters, setFilters] = useState<ChipFilterCriteria[]>([]);
    const [filterDateFrom, setFilterDateFrom] = useState("");
    const [filterDateTo, setFilterDateTo] = useState("");

    // Summary stats
    const [totalCost, setTotalCost] = useState(0);
    const [totalInputTokens, setTotalInputTokens] = useState(0);
    const [totalOutputTokens, setTotalOutputTokens] = useState(0);

    const filterInvocationType = filters.find((f) => f.filterBy.value === "invocationType")?.filterValue;
    const filterActionType = filters.find((f) => f.filterBy.value === "actionType")?.filterValue;
    const filterEngine = filters.find((f) => f.filterBy.value === "engine")?.filterValue;
    const filterModel = filters.find((f) => f.filterBy.value === "model")?.filterValue;
    const isFiltered = filters.length > 0 || !!filterDateFrom || !!filterDateTo;

    const loadData = useCallback(() => {
        setLoading(true);
        fetchUsage(
            page, perPage,
            filterInvocationType || undefined,
            undefined, undefined,
            filterActionType || undefined,
            filterDateFrom || undefined,
            filterDateTo || undefined,
            undefined,
            filterEngine || undefined,
            filterModel || undefined
        )
            .then((results) => {
                setRecords(results.items);
                setTotalCount(results.totalCount);
                setTotalCost(results.totalCostUsd || 0);
                setTotalInputTokens(results.totalInputTokens || 0);
                setTotalOutputTokens(results.totalOutputTokens || 0);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterInvocationType, filterActionType, filterDateFrom, filterDateTo,
        filterEngine, filterModel]);

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
        setFilterDateFrom("");
        setFilterDateTo("");
        setPage(1);
    };

    return (
        <PageSection>
            <Title headingLevel="h1" size="lg" style={{ marginBottom: "16px" }}>
                AI Usage
            </Title>

            <Gallery hasGutter minWidths={{ default: "180px" }} style={{ marginBottom: "16px" }}>
                <GalleryItem>
                    <Card isCompact>
                        <CardBody style={{ textAlign: "center", padding: "16px" }}>
                            <div style={{ fontSize: "24px", fontWeight: "bold" }}>
                                {totalCount}
                            </div>
                            <div className="axiom-text-subtle" style={{ fontSize: "13px" }}>
                                Invocations
                            </div>
                        </CardBody>
                    </Card>
                </GalleryItem>
                <GalleryItem>
                    <Card isCompact>
                        <CardBody style={{ textAlign: "center", padding: "16px" }}>
                            <div style={{ fontSize: "24px", fontWeight: "bold" }}>
                                ${totalCost.toFixed(4)}
                            </div>
                            <div className="axiom-text-subtle" style={{ fontSize: "13px" }}>
                                Cost
                            </div>
                        </CardBody>
                    </Card>
                </GalleryItem>
                <GalleryItem>
                    <Card isCompact>
                        <CardBody style={{ textAlign: "center", padding: "16px" }}>
                            <div style={{ fontSize: "24px", fontWeight: "bold" }}>
                                {totalInputTokens.toLocaleString()}
                            </div>
                            <div className="axiom-text-subtle" style={{ fontSize: "13px" }}>
                                Input Tokens
                            </div>
                        </CardBody>
                    </Card>
                </GalleryItem>
                <GalleryItem>
                    <Card isCompact>
                        <CardBody style={{ textAlign: "center", padding: "16px" }}>
                            <div style={{ fontSize: "24px", fontWeight: "bold" }}>
                                {totalOutputTokens.toLocaleString()}
                            </div>
                            <div className="axiom-text-subtle" style={{ fontSize: "13px" }}>
                                Output Tokens
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
                    <ToolbarItem variant="label">From</ToolbarItem>
                    <ToolbarItem>
                        <TextInput type="date" aria-label="From date" value={filterDateFrom}
                            onChange={(_e, v) => { setFilterDateFrom(v); setPage(1); }}
                            style={{ width: "160px" }} />
                    </ToolbarItem>
                    <ToolbarItem variant="label">To</ToolbarItem>
                    <ToolbarItem>
                        <TextInput type="date" aria-label="To date" value={filterDateTo}
                            onChange={(_e, v) => { setFilterDateTo(v); setPage(1); }}
                            style={{ width: "160px" }} />
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
                        <EmptyStateBody>Loading usage data...</EmptyStateBody>
                    </EmptyState>
                ) : records.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>
                            {isFiltered
                                ? "No usage records match the current filters."
                                : "No AI usage recorded yet."}
                        </EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="AI Usage" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Time</Th>
                                <Th>Type</Th>
                                <Th>Action</Th>
                                <Th>Engine</Th>
                                <Th>Model</Th>
                                <Th>Project</Th>
                                <Th>Cost</Th>
                                <Th>Input Tokens</Th>
                                <Th>Output Tokens</Th>
                            </Tr>
                        </Thead>
                        <Tbody>
                            {records.map((r) => (
                                <Tr key={r.id}>
                                    <Td style={{ whiteSpace: "nowrap" }}>
                                        {new Date(r.createdOn).toLocaleString()}
                                    </Td>
                                    <Td>
                                        <Label isCompact
                                            color={TYPE_COLORS[r.invocationType] || "grey"}
                                            style={{ cursor: "pointer" }}
                                            onClick={() => {
                                                const typeFilter = FILTER_TYPES.find((t) => t.value === "invocationType")!;
                                                onAddFilterCriteria({ filterBy: typeFilter, filterValue: r.invocationType });
                                            }}>
                                            {r.invocationType}
                                        </Label>
                                    </Td>
                                    <Td>{r.actionType || "—"}</Td>
                                    <Td>
                                        {r.engine ? (
                                            <Label isCompact color="purple"
                                                style={{ cursor: "pointer" }}
                                                onClick={() => {
                                                    const engineFilter = FILTER_TYPES.find(
                                                        (t) => t.value === "engine")!;
                                                    onAddFilterCriteria({
                                                        filterBy: engineFilter,
                                                        filterValue: r.engine!,
                                                    });
                                                }}>
                                                {r.engine}
                                            </Label>
                                        ) : "—"}
                                    </Td>
                                    <Td>
                                        {r.model ? (
                                            <Label isCompact color="cyan"
                                                style={{ cursor: "pointer" }}
                                                onClick={() => {
                                                    const modelFilter = FILTER_TYPES.find(
                                                        (t) => t.value === "model")!;
                                                    onAddFilterCriteria({
                                                        filterBy: modelFilter,
                                                        filterValue: r.model!,
                                                    });
                                                }}>
                                                {r.model}
                                            </Label>
                                        ) : "—"}
                                    </Td>
                                    <Td>
                                        {r.projectId ? `Project #${r.projectId}` : "—"}
                                    </Td>
                                    <Td>
                                        {r.costUsd != null
                                            ? `$${r.costUsd.toFixed(4)}`
                                            : "—"}
                                    </Td>
                                    <Td>
                                        {r.inputTokens != null
                                            ? r.inputTokens.toLocaleString()
                                            : "—"}
                                    </Td>
                                    <Td>
                                        {r.outputTokens != null
                                            ? r.outputTokens.toLocaleString()
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
