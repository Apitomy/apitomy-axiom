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
import {
    type ChipFilterCriteria,
    type ChipFilterType,
    ChipFilterInput,
    FilterChips,
} from "@apitomy/common-ui-components";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import {
    type WorkflowDefinition, type WorkflowRunSummary,
    fetchWorkflowDefinitions, fetchWorkflowRuns,
} from "../config/api";
import { sseClient, type AxiomSseEvent } from "../config/sse";
import { STATUS_COLORS } from "../components/workflowRun";

const FILTER_TYPES: ChipFilterType[] = [
    { value: "status", label: "Status", testId: "runs-filter-status" },
    { value: "workflow", label: "Workflow", testId: "runs-filter-workflow" },
    { value: "subject", label: "Subject ID", testId: "runs-filter-subject" },
    { value: "label", label: "Label", testId: "runs-filter-label" },
];

/** Filter types that can appear several times (each value is an extra condition); the others replace. */
const MULTI_VALUE_FILTERS: ReadonlySet<string> = new Set<string>(["label"]);

/**
 * Normalises a filter value typed by the user, or returns null when it is not valid for the filter type.
 *
 * @param type the filter type value
 * @param raw the typed value
 * @returns the value to filter by, or null
 */
export function normalizeRunFilter(type: string, raw: string): string | null {
    const value: string = raw.trim();
    if (!value) {
        return null;
    }
    switch (type) {
        case "status":
            return value.toLowerCase();
        case "subject":
            return /^\d+$/.test(value) ? value : null;
        case "label":
            return /^[^:\s]+:.+$/.test(value) ? value : null;
        default:
            return value;
    }
}

/**
 * Resolves a "Workflow" filter value (a workflow name, case-insensitive, or a numeric id) to a definition id.
 *
 * @param value the filter value
 * @param definitions the known workflow definitions
 * @returns the definition id, or -1 when nothing matches (so the list shows no runs)
 */
export function resolveWorkflowFilter(value: string, definitions: WorkflowDefinition[]): number {
    const byName: WorkflowDefinition | undefined = definitions.find(
        (d) => d.name.toLowerCase() === value.toLowerCase());
    if (byName) {
        return byName.id;
    }
    return /^\d+$/.test(value) ? Number(value) : -1;
}

function formatDuration(startedOn: string, completedOn?: string): string {
    if (!completedOn) return "—";
    const ms = new Date(completedOn).getTime() - new Date(startedOn).getTime();
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
    const mins = Math.floor(ms / 60_000);
    const secs = Math.round((ms % 60_000) / 1000);
    return `${mins}m ${secs}s`;
}

/** Lists workflow runs, filtered by status, workflow, subject and labels using filter chips. */
export function WorkflowRunsPage() {
    const [runs, setRuns] = useState<WorkflowRunSummary[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [loading, setLoading] = useState(true);
    const [filters, setFilters] = useState<ChipFilterCriteria[]>([]);
    const [definitions, setDefinitions] = useState<WorkflowDefinition[]>([]);

    useEffect(() => {
        fetchWorkflowDefinitions(1, 100)
            .then((results) => setDefinitions(results.items))
            .catch(console.error);
    }, []);

    const valueOf = (type: string): string | undefined => filters.find((f) => f.filterBy.value === type)?.filterValue;
    const statusFilter: string | undefined = valueOf("status");
    const workflowFilter: string | undefined = valueOf("workflow");
    const subjectFilter: string | undefined = valueOf("subject");
    const labelFilters: string = filters.filter((f) => f.filterBy.value === "label").map((f) => f.filterValue).join("\n");
    const isFiltered: boolean = filters.length > 0;
    const definitionId: number | undefined = workflowFilter === undefined ? undefined
        : resolveWorkflowFilter(workflowFilter, definitions);

    const loadData = useCallback(() => {
        setLoading(true);
        fetchWorkflowRuns(page, perPage, {
            status: statusFilter,
            definitionId,
            subjectId: subjectFilter !== undefined ? Number(subjectFilter) : undefined,
            labels: labelFilters ? labelFilters.split("\n") : [],
        })
            .then((results) => {
                setRuns(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, statusFilter, definitionId, subjectFilter, labelFilters]);

    useEffect(() => { loadData(); }, [loadData]);

    useEffect(() => {
        let timeout: ReturnType<typeof setTimeout>;
        const unsubscribe = sseClient.subscribe((event: AxiomSseEvent) => {
            if (event.type === "workflow-updated") {
                clearTimeout(timeout);
                timeout = setTimeout(loadData, 300);
            }
        });
        return () => { clearTimeout(timeout); unsubscribe(); };
    }, [loadData]);

    const onAddFilterCriteria = (criteria: ChipFilterCriteria) => {
        const type: string = criteria.filterBy.value;
        const value: string | null = normalizeRunFilter(type, criteria.filterValue);
        if (value === null) {
            return;
        }
        const kept: ChipFilterCriteria[] = MULTI_VALUE_FILTERS.has(type)
            ? filters.filter((f) => !(f.filterBy.value === type && f.filterValue === value))
            : filters.filter((f) => f.filterBy.value !== type);
        setFilters([...kept, { filterBy: criteria.filterBy, filterValue: value }]);
        setPage(1);
    };

    const addFilter = (type: string, value: string) => {
        const filterBy: ChipFilterType | undefined = FILTER_TYPES.find((t) => t.value === type);
        if (filterBy) {
            onAddFilterCriteria({ filterBy, filterValue: value });
        }
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
                Runs
            </Title>

            <Toolbar>
                <ToolbarContent>
                    <ToolbarItem>
                        <ChipFilterInput filterTypes={FILTER_TYPES} onAddCriteria={onAddFilterCriteria} />
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
                            <FilterChips criteria={filters} onClearAllCriteria={onClearAllFilters}
                                onRemoveCriteria={onRemoveFilterCriteria} />
                        </ToolbarItem>
                    </ToolbarContent>
                </Toolbar>
            )}

            {loading ? (
                <EmptyState><EmptyStateBody>Loading workflow runs...</EmptyStateBody></EmptyState>
            ) : runs.length === 0 ? (
                <EmptyState>
                    <EmptyStateBody>
                        {isFiltered ? "No workflow runs match the current filters." : "No workflow runs recorded yet."}
                    </EmptyStateBody>
                </EmptyState>
            ) : (
                <Table aria-label="Workflow Runs" variant="compact">
                    <Thead>
                        <Tr>
                            <Th>Status</Th>
                            <Th>Title</Th>
                            <Th>Subject</Th>
                            <Th>Workflow</Th>
                            <Th>Started</Th>
                            <Th>Duration</Th>
                            <Th>Actions</Th>
                        </Tr>
                    </Thead>
                    <Tbody>
                        {runs.map((run) => (
                            <Tr key={run.runId}>
                                <Td>
                                    <Label isCompact color={STATUS_COLORS[run.status] || "grey"}
                                        onClick={() => addFilter("status", run.status)}>
                                        {run.status}
                                    </Label>
                                </Td>
                                <Td>
                                    <Link to={`/runs/${run.runId}`}>{run.title}</Link>
                                </Td>
                                <Td>
                                    <Link to={`/subjects/${run.subjectId}`}>
                                        {run.subjectName || `Subject #${run.subjectId}`}
                                    </Link>
                                </Td>
                                <Td>
                                    <Button variant="link" isInline
                                        onClick={() => addFilter("workflow", run.definitionName || String(run.definitionId))}>
                                        {run.definitionName || `Definition #${run.definitionId}`}
                                    </Button>
                                    {" v"}{run.definitionVersion}
                                </Td>
                                <Td style={{ whiteSpace: "nowrap" }}>
                                    {new Date(run.startedOn).toLocaleString()}
                                </Td>
                                <Td style={{ whiteSpace: "nowrap" }}>
                                    {formatDuration(run.startedOn, run.completedOn)}
                                </Td>
                                <Td>
                                    <Link to={`/runs/${run.runId}`}>
                                        View details
                                    </Link>
                                </Td>
                            </Tr>
                        ))}
                    </Tbody>
                </Table>
            )}
        </PageSection>
    );
}
