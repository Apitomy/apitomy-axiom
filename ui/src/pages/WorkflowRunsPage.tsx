import { useState, useEffect, useCallback } from "react";
import { Link } from "react-router-dom";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    FormSelect,
    FormSelectOption,
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
    type WorkflowDefinition, type WorkflowRunSummary,
    fetchWorkflowDefinitions, fetchWorkflowRuns,
} from "../config/api";
import { sseClient, type AxiomSseEvent } from "../config/sse";

const STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    running: "blue",
    waiting: "orange",
    completed: "green",
    failed: "red",
    cancelled: "grey",
};

function formatDuration(startedOn: string, completedOn?: string): string {
    if (!completedOn) return "—";
    const ms = new Date(completedOn).getTime() - new Date(startedOn).getTime();
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
    const mins = Math.floor(ms / 60_000);
    const secs = Math.round((ms % 60_000) / 1000);
    return `${mins}m ${secs}s`;
}

/** Lists workflow runs with status, definition, subject and label filters. */
export function WorkflowRunsPage() {
    const [runs, setRuns] = useState<WorkflowRunSummary[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [loading, setLoading] = useState(true);
    const [statusFilter, setStatusFilter] = useState("");
    const [definitionFilter, setDefinitionFilter] = useState("");
    const [subjectFilter, setSubjectFilter] = useState("");
    const [labelFilter, setLabelFilter] = useState("");
    const [definitions, setDefinitions] = useState<WorkflowDefinition[]>([]);

    useEffect(() => {
        fetchWorkflowDefinitions(1, 100)
            .then((results) => setDefinitions(results.items))
            .catch(console.error);
    }, []);

    const loadData = useCallback(() => {
        setLoading(true);
        const subjectId: number = Number(subjectFilter);
        const labels: string[] = labelFilter.split(",")
            .map((l) => l.trim())
            .filter((l) => l.includes(":"));
        fetchWorkflowRuns(page, perPage, {
            status: statusFilter || undefined,
            definitionId: definitionFilter ? Number(definitionFilter) : undefined,
            subjectId: subjectFilter.trim() && Number.isInteger(subjectId) ? subjectId : undefined,
            labels,
        })
            .then((results) => {
                setRuns(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, statusFilter, definitionFilter, subjectFilter, labelFilter]);

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

    return (
        <PageSection>
            <Title headingLevel="h1" size="lg" style={{ marginBottom: "16px" }}>
                Runs
            </Title>

            <Toolbar>
                <ToolbarContent>
                    <ToolbarItem>
                        <FormSelect aria-label="Status" value={statusFilter}
                            onChange={(_e, v) => { setStatusFilter(v); setPage(1); }}>
                            <FormSelectOption value="" label="All statuses" />
                            {Object.keys(STATUS_COLORS).map((st) => (
                                <FormSelectOption key={st} value={st} label={st} />
                            ))}
                        </FormSelect>
                    </ToolbarItem>
                    <ToolbarItem>
                        <FormSelect aria-label="Definition" value={definitionFilter}
                            onChange={(_e, v) => { setDefinitionFilter(v); setPage(1); }}>
                            <FormSelectOption value="" label="All workflows" />
                            {definitions.map((d) => (
                                <FormSelectOption key={d.id} value={String(d.id)} label={d.name} />
                            ))}
                        </FormSelect>
                    </ToolbarItem>
                    <ToolbarItem>
                        <TextInput aria-label="Subject" placeholder="Subject ID" type="number"
                            value={subjectFilter}
                            onChange={(_e, v) => { setSubjectFilter(v); setPage(1); }} />
                    </ToolbarItem>
                    <ToolbarItem>
                        <TextInput aria-label="Label" placeholder="key:value, key:value"
                            value={labelFilter}
                            onChange={(_e, v) => { setLabelFilter(v); setPage(1); }} />
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

            {loading ? (
                <EmptyState><EmptyStateBody>Loading workflow runs...</EmptyStateBody></EmptyState>
            ) : runs.length === 0 ? (
                <EmptyState><EmptyStateBody>No workflow runs recorded yet.</EmptyStateBody></EmptyState>
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
                                    <Label isCompact color={STATUS_COLORS[run.status] || "grey"}>
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
                                    {run.definitionName || `Definition #${run.definitionId}`}
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
