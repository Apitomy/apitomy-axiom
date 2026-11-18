import { useState, useEffect, useCallback, useMemo, type ComponentType } from "react";
import { useNavigate } from "react-router-dom";
import {
    Button,
    Content,
    EmptyState,
    EmptyStateBody,
    PageSection,
    Pagination,
    Title,
    Toolbar,
    ToolbarContent,
    ToolbarItem,
    Tooltip,
} from "@patternfly/react-core";
import {
    type ChipFilterCriteria,
    type ChipFilterType,
    ChipFilterInput,
    FilterChips,
} from "@apitomy/common-ui-components";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import InProgressIcon from "@patternfly/react-icons/dist/esm/icons/in-progress-icon";
import PauseCircleIcon from "@patternfly/react-icons/dist/esm/icons/pause-circle-icon";
import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";
import BanIcon from "@patternfly/react-icons/dist/esm/icons/ban-icon";
import QuestionCircleIcon from "@patternfly/react-icons/dist/esm/icons/question-circle-icon";
import {
    type WorkflowDefinition, type WorkflowRunSummary,
    fetchWorkflowDefinitions, fetchWorkflowRuns,
} from "../config/api";
import { sseClient, type AxiomSseEvent } from "../config/sse";
import { STATUS_COLORS } from "../components/workflowRun";
import { ColoredLabel } from "../components/ColoredLabel";
import { RunAiSummaryHeadline } from "../components/RunAiSummaryCard";
import { useListUrlState } from "../hooks/useListUrlState";
import type { ListStateConfig } from "../utils/listUrlState";

/** Page size used when loading all workflow definitions for the Workflow filter. */
const DEFINITION_PAGE_SIZE = 100;

/**
 * Builds the filter types for the runs list. When workflow definitions are known, the Workflow filter offers
 * them as a select (value = definition id, label = name); otherwise it falls back to free text.
 *
 * @param definitions the known workflow definitions, or null while they are loading
 * @returns the filter types
 */
export function buildFilterTypes(definitions: WorkflowDefinition[] | null): ChipFilterType[] {
    return [
        { value: "status", label: "Status", testId: "runs-filter-status" },
        {
            value: "workflow", label: "Workflow", testId: "runs-filter-workflow",
            options: definitions?.map((d) => ({ value: String(d.id), label: d.name || `Workflow #${d.id}` })),
        },
        { value: "subject", label: "Subject ID", testId: "runs-filter-subject" },
        { value: "label", label: "Label", testId: "runs-filter-label" },
    ];
}

/** Loads every workflow definition, following pagination. */
async function fetchAllDefinitions(): Promise<WorkflowDefinition[]> {
    const all: WorkflowDefinition[] = [];
    for (let page = 1; ; page++) {
        const results = await fetchWorkflowDefinitions(page, DEFINITION_PAGE_SIZE);
        all.push(...results.items);
        if (results.items.length < DEFINITION_PAGE_SIZE || all.length >= results.totalCount) {
            return all;
        }
    }
}

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
            return value.trim() || null;
        default:
            return value;
    }
}

/**
 * Resolves a "Workflow" filter value (a definition id, as selected from the filter, or — for older links — a
 * workflow name, case-insensitive) to a definition id.
 *
 * @param value the filter value
 * @param definitions the known workflow definitions
 * @returns the definition id, or -1 when nothing matches (so the list shows no runs)
 */
export function resolveWorkflowFilter(value: string, definitions: WorkflowDefinition[]): number {
    if (/^\d+$/.test(value) && definitions.some((d) => d.id === Number(value))) {
        return Number(value);
    }
    const byName: WorkflowDefinition | undefined = definitions.find(
        (d) => d.name.toLowerCase() === value.toLowerCase());
    if (byName) {
        return byName.id;
    }
    return /^\d+$/.test(value) ? Number(value) : -1;
}

/**
 * Describes what a run ran on: its subject's name.
 *
 * @param run the run summary
 * @returns the run's subject description
 */
export function describeRun(run: WorkflowRunSummary): string {
    return run.subjectName || `Subject #${run.subjectId}`;
}

/**
 * Describes the workflow a run came from: "workflow vN".
 *
 * @param run the run summary
 * @returns the run's workflow name and version
 */
export function describeRunWorkflow(run: WorkflowRunSummary): string {
    const workflow: string = run.definitionName || `Workflow #${run.definitionId}`;
    return `${workflow} v${run.definitionVersion}`;
}

/**
 * Returns the "Workflow" filter value for a run: its definition id (matching the Workflow filter's options).
 *
 * @param run the run summary
 * @returns the filter value
 */
export function workflowFilterValue(run: WorkflowRunSummary): string {
    return String(run.definitionId);
}

/** Icons of workflow run statuses; unknown statuses use a generic icon. */
const STATUS_ICONS: Record<string, ComponentType> = {
    running: InProgressIcon,
    waiting: PauseCircleIcon,
    completed: CheckCircleIcon,
    failed: ExclamationCircleIcon,
    cancelled: BanIcon,
};

/**
 * A clickable, colour-coded icon for a run status (same hues as the status labels), with a tooltip naming it.
 *
 * @param props the status and the click handler (the click does not reach the table row)
 */
function RunStatusIcon({ status, onClick }: { status: string; onClick: () => void }) {
    const Icon: ComponentType = STATUS_ICONS[status] ?? QuestionCircleIcon;
    const hue: string = (STATUS_COLORS[status] || "grey") === "grey" ? "gray" : STATUS_COLORS[status];
    return (
        <Tooltip content={status}>
            <Button variant="plain" size="sm" aria-label={`Filter by status: ${status}`}
                style={{ color: `var(--pf-t--global--color--nonstatus--${hue}--400)`, padding: 0 }}
                onClick={(e) => { e.stopPropagation(); onClick(); }}>
                <Icon />
            </Button>
        </Tooltip>
    );
}

/**
 * Lists workflow runs, filtered by status, workflow, subject and labels using filter chips. Clicking a row
 * opens the run.
 */
export function WorkflowRunsPage() {
    const navigate = useNavigate();
    const [runs, setRuns] = useState<WorkflowRunSummary[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [loading, setLoading] = useState(true);
    const [loadedDefinitions, setLoadedDefinitions] = useState<WorkflowDefinition[] | null>(null);
    const definitions: WorkflowDefinition[] = useMemo(() => loadedDefinitions ?? [], [loadedDefinitions]);
    const filterTypes: ChipFilterType[] = useMemo(() => buildFilterTypes(loadedDefinitions), [loadedDefinitions]);
    const listConfig: ListStateConfig = useMemo(
        () => ({ filterTypes, multiValueFilters: MULTI_VALUE_FILTERS }), [filterTypes]);
    const { filters, page, perPage, setFilters, setPage, setPerPage } = useListUrlState(listConfig);

    useEffect(() => {
        let cancelled = false;
        fetchAllDefinitions()
            .then((items) => { if (!cancelled) setLoadedDefinitions(items); })
            .catch(console.error);
        return () => { cancelled = true; };
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
        const label: string | undefined = criteria.filterBy.options?.find((o) => o.value === value)?.label;
        setFilters([...kept, label && label !== value
            ? { filterBy: criteria.filterBy, filterValue: value, filterLabel: label }
            : { filterBy: criteria.filterBy, filterValue: value }]);
    };

    const addFilter = (type: string, value: string) => {
        const filterBy: ChipFilterType | undefined = filterTypes.find((t) => t.value === type);
        if (filterBy) {
            onAddFilterCriteria({ filterBy, filterValue: value });
        }
    };

    const onRemoveFilterCriteria = (criteria: ChipFilterCriteria) => {
        setFilters(filters.filter((f) =>
            !(f.filterBy.value === criteria.filterBy.value && f.filterValue === criteria.filterValue)));
    };

    const onClearAllFilters = () => {
        setFilters([]);
    };

    return (
        <PageSection>
            <Title headingLevel="h1" size="lg" style={{ marginBottom: "16px" }}>
                Runs
            </Title>

            <Toolbar>
                <ToolbarContent>
                    <ToolbarItem>
                        <ChipFilterInput filterTypes={filterTypes} onAddCriteria={onAddFilterCriteria} />
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
                            <Th>Run</Th>
                            <Th>Workflow</Th>
                            <Th>Labels</Th>
                            <Th>Started</Th>
                        </Tr>
                    </Thead>
                    <Tbody>
                        {runs.map((run) => (
                            <Tr key={run.runId} isClickable onRowClick={() => navigate(`/runs/${run.runId}`)}>
                                <Td modifier="fitContent">
                                    <RunStatusIcon status={run.status} onClick={() => addFilter("status", run.status)} />
                                </Td>
                                <Td>
                                    <div>{describeRun(run)}</div>
                                    {run.title && (
                                        <Content component="small" className="axiom-run-title">{run.title}</Content>
                                    )}
                                    <RunAiSummaryHeadline summary={run.aiSummary} />
                                </Td>
                                <Td style={{ whiteSpace: "nowrap" }}>
                                    <Button variant="link" isInline aria-label={`Filter by workflow: ${describeRunWorkflow(run)}`}
                                        onClick={(e) => {
                                            e.stopPropagation();
                                            addFilter("workflow", workflowFilterValue(run));
                                        }}>
                                        {describeRunWorkflow(run)}
                                    </Button>
                                </Td>
                                <Td>
                                    {run.labels?.map((label) => (
                                        <ColoredLabel key={label} isCompact
                                            style={{ marginRight: "4px", cursor: "pointer" }}
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                addFilter("label", label);
                                            }}>
                                            {label}
                                        </ColoredLabel>
                                    ))}
                                </Td>
                                <Td style={{ whiteSpace: "nowrap" }}>
                                    {new Date(run.startedOn).toLocaleString()}
                                </Td>
                            </Tr>
                        ))}
                    </Tbody>
                </Table>
            )}
        </PageSection>
    );
}
