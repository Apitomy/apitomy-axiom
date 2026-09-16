import { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { useEffectiveTheme } from "../hooks/useTheme";
import { useParams, Link, useNavigate } from "react-router-dom";
import {
    Alert,
    AlertActionCloseButton,
    Breadcrumb,
    BreadcrumbItem,
    Button,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    FormSelect,
    FormSelectOption,
    Label,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    PageSection,
    Pagination,
    Tab,
    Tabs,
    TabTitleText,
    TextArea,
    TextInput,
    Title,
    Toolbar,
    ToolbarContent,
    ToolbarItem,
    Tooltip,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import {
    WorkflowEditor,
    WorkflowDiffViewer,
} from "@apitomy/flow-ui";
import type { Workflow, ValidationProblem, EditorSpi, ActionTypeDescriptor } from "@apitomy/flow-ui";
import {
    type WorkflowDefinition,
    type WorkflowDefinitionVersion,
    type UpdateWorkflowDefinition,
    getWorkflowDefinition,
    updateWorkflowDefinition,
    updateWorkflowDefinitionContent,
    publishWorkflowDefinition,
    deleteWorkflowDefinition,
    listWorkflowDefinitionVersions,
    getWorkflowDefinitionVersion,
    fetchWorkflowDefinitionRuns,
    fetchActionTypes,
    type WorkflowRunSummary,
} from "../config/api";
import { sseClient, type AxiomSseEvent } from "../config/sse";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import RocketIcon from "@patternfly/react-icons/dist/esm/icons/rocket-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import EditIcon from "@patternfly/react-icons/dist/esm/icons/edit-icon";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import CodeBranchIcon from "@patternfly/react-icons/dist/esm/icons/code-branch-icon";

const RUN_STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
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

export function WorkflowDefinitionDetailPage() {
    const { workflowDefinitionId } = useParams<{ workflowDefinitionId: string }>();
    const id = Number(workflowDefinitionId);
    const navigate = useNavigate();
    const effectiveTheme = useEffectiveTheme();

    const [definition, setDefinition] = useState<WorkflowDefinition | null>(null);
    const [editorContent, setEditorContent] = useState<Workflow | null>(null);
    const [dirty, setDirty] = useState(false);
    const savedContentRef = useRef<string>("");
    const [saving, setSaving] = useState(false);
    const [publishing, setPublishing] = useState(false);
    const [loading, setLoading] = useState(true);
    const [validationErrors, setValidationErrors] = useState<ValidationProblem[]>([]);
    const [versions, setVersions] = useState<WorkflowDefinitionVersion[]>([]);
    const [activeTab, setActiveTab] = useState(0);
    const [runs, setRuns] = useState<WorkflowRunSummary[]>([]);
    const [runsTotalCount, setRunsTotalCount] = useState(0);
    const [runsPage, setRunsPage] = useState(1);
    const [runsPerPage, setRunsPerPage] = useState(20);
    const [runsLoading, setRunsLoading] = useState(false);
    const [runsLoaded, setRunsLoaded] = useState(false);
    const [editMetadataOpen, setEditMetadataOpen] = useState(false);
    const [metadataForm, setMetadataForm] = useState({ name: "", description: "" });
    const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false);
    const [deleteError, setDeleteError] = useState<string | null>(null);
    const [compareBaseVersion, setCompareBaseVersion] = useState<number | null>(null);
    const [compareTargetVersion, setCompareTargetVersion] = useState<number | null>(null);
    const [diffOpen, setDiffOpen] = useState(false);
    const [diffLoading, setDiffLoading] = useState(false);
    const [diffError, setDiffError] = useState<string | null>(null);
    const [diffBaseWorkflow, setDiffBaseWorkflow] = useState<Workflow | null>(null);
    const [diffCompareWorkflow, setDiffCompareWorkflow] = useState<Workflow | null>(null);

    // EditorSpi for action types — memoized to avoid re-renders
    const spi: EditorSpi = useMemo(() => ({
        actionTypes: async () => {
            const results = await fetchActionTypes(1, 1000, undefined, undefined, undefined, true);
            return results.items.map((at): ActionTypeDescriptor => ({
                value: at.name,
                label: at.name,
                description: at.description,
                inputs: at.inputs,
                outputs: at.outputs,
            }));
        },
    }), []);

    const loadDefinition = useCallback(() => {
        if (!id) return;
        setLoading(true);
        Promise.all([
            getWorkflowDefinition(id),
            listWorkflowDefinitionVersions(id),
        ])
            .then(([def, vers]) => {
                setDefinition(def);
                setEditorContent(def.content || null);
                savedContentRef.current = JSON.stringify(def.content || null);
                setVersions(vers);
                setDirty(false);
                if (vers.length >= 2) {
                    const sorted = [...vers].sort((a, b) => b.version - a.version);
                    setCompareBaseVersion(sorted[1].version);
                    setCompareTargetVersion(sorted[0].version);
                }
            })
            .catch((err) => {
                console.error("Failed to load workflow definition:", err);
            })
            .finally(() => setLoading(false));
    }, [id]);

    useEffect(() => {
        loadDefinition();
    }, [loadDefinition]);

    // beforeunload guard
    useEffect(() => {
        const handler = (e: BeforeUnloadEvent) => {
            if (dirty) {
                e.preventDefault();
            }
        };
        window.addEventListener("beforeunload", handler);
        return () => window.removeEventListener("beforeunload", handler);
    }, [dirty]);

    const loadRuns = useCallback(() => {
        if (!id) return;
        setRunsLoading(true);
        fetchWorkflowDefinitionRuns(id, runsPage, runsPerPage)
            .then((results) => {
                setRuns(results.items);
                setRunsTotalCount(results.totalCount);
                setRunsLoaded(true);
            })
            .catch((err) => {
                console.error("Failed to load workflow runs:", err);
            })
            .finally(() => setRunsLoading(false));
    }, [id, runsPage, runsPerPage]);

    // Lazy-load runs the first time the Runs tab is shown, and whenever
    // its pagination changes while it is active.
    useEffect(() => {
        if (activeTab === 2) {
            loadRuns();
        }
    }, [activeTab, loadRuns]);

    // Keep the Runs tab in sync with workflow lifecycle events (debounced).
    useEffect(() => {
        if (activeTab !== 2) return;
        let timeout: ReturnType<typeof setTimeout>;
        const unsubscribe = sseClient.subscribe((event: AxiomSseEvent) => {
            if (event.type === "workflow-updated") {
                clearTimeout(timeout);
                timeout = setTimeout(loadRuns, 300);
            }
        });
        return () => { clearTimeout(timeout); unsubscribe(); };
    }, [activeTab, loadRuns]);

    const handleEditorChange = useCallback((updated: Workflow) => {
        setEditorContent(updated);
        const isDirty = JSON.stringify(updated) !== savedContentRef.current;
        setDirty(isDirty);
    }, []);

    const handleValidationChange = useCallback((problems: ValidationProblem[]) => {
        setValidationErrors(problems.filter((p) => p.severity === "error"));
    }, []);

    const handleSave = () => {
        if (!editorContent) return;
        setSaving(true);
        updateWorkflowDefinitionContent(id, editorContent)
            .then(() => {
                savedContentRef.current = JSON.stringify(editorContent);
                setDirty(false);
            })
            .catch((err) => {
                console.error("Failed to save workflow content:", err);
            })
            .finally(() => setSaving(false));
    };

    const handlePublish = () => {
        setPublishing(true);
        publishWorkflowDefinition(id)
            .then(() => {
                loadDefinition();
            })
            .catch((err) => {
                console.error("Failed to publish workflow definition:", err);
            })
            .finally(() => setPublishing(false));
    };

    const handleEditMetadata = () => {
        if (!definition) return;
        setMetadataForm({
            name: definition.name,
            description: definition.description || "",
        });
        setEditMetadataOpen(true);
    };

    const handleSaveMetadata = () => {
        const updates: UpdateWorkflowDefinition = {
            name: metadataForm.name,
            description: metadataForm.description || undefined,
        };
        updateWorkflowDefinition(id, updates)
            .then((updated) => {
                setDefinition(updated);
                setEditMetadataOpen(false);
            })
            .catch((err) => {
                console.error("Failed to update metadata:", err);
            });
    };

    const handleDelete = () => {
        setDeleteError(null);
        deleteWorkflowDefinition(id)
            .then(() => {
                setDeleteConfirmOpen(false);
                navigate("/components/workflows");
            })
            .catch((err) => {
                console.error("Failed to delete workflow definition:", err);
                setDeleteError("Failed to delete this workflow. Please try again.");
            });
    };

    /**
     * Resolves the full content for a given version, fetching it from the server
     * when the already-loaded version summary does not include content.
     */
    const resolveVersionContent = useCallback(
        async (version: number): Promise<Workflow> => {
            const cached = versions.find((v) => v.version === version);
            if (cached?.content) {
                return cached.content as Workflow;
            }
            const fetched = await getWorkflowDefinitionVersion(id, version);
            return fetched.content as Workflow;
        },
        [versions, id]
    );

    /**
     * Opens the diff modal comparing two explicit version numbers. Used by both the
     * manual "compare versions" toolbar (reading its own dropdown selections) and the
     * per-row "diff vs previous" action (which knows its pair without touching the
     * dropdown state at all).
     */
    const openDiff = (baseVersion: number, targetVersion: number) => {
        setDiffError(null);
        setDiffLoading(true);
        setDiffOpen(true);
        Promise.all([
            resolveVersionContent(baseVersion),
            resolveVersionContent(targetVersion),
        ])
            .then(([base, compare]) => {
                setDiffBaseWorkflow(base);
                setDiffCompareWorkflow(compare);
            })
            .catch((err) => {
                console.error("Failed to load versions for comparison:", err);
                setDiffError("Failed to load one or both versions for comparison.");
            })
            .finally(() => setDiffLoading(false));
    };

    const handleShowDiff = () => {
        if (compareBaseVersion === null || compareTargetVersion === null) return;
        openDiff(compareBaseVersion, compareTargetVersion);
    };

    /** Opens the diff modal comparing a version against the one immediately before it. */
    const handleDiffVsPrevious = (version: number, previousVersion: number) => {
        openDiff(previousVersion, version);
    };

    if (loading) {
        return (
            <PageSection>
                <EmptyState>
                    <EmptyStateBody>Loading workflow definition...</EmptyStateBody>
                </EmptyState>
            </PageSection>
        );
    }

    if (!definition) {
        return (
            <PageSection>
                <EmptyState>
                    <EmptyStateBody>Workflow definition not found.</EmptyStateBody>
                </EmptyState>
            </PageSection>
        );
    }

    const hasErrors = validationErrors.length > 0;
    const canPublish = !dirty && !publishing && !hasErrors;

    return (
        <PageSection isFilled className="workflow-definition-detail" style={{ padding: 0 }}>
            {/* Header bar */}
            <div style={{ padding: "16px 24px", borderBottom: "1px solid var(--pf-t--global--border--color--default)" }}>
                <Breadcrumb style={{ marginBottom: "16px" }}>
                    <BreadcrumbItem>
                        <Link to="/">Components</Link>
                    </BreadcrumbItem>
                    <BreadcrumbItem>
                        <Link to="/components/workflows">Workflows</Link>
                    </BreadcrumbItem>
                    <BreadcrumbItem isActive>{definition.name}</BreadcrumbItem>
                </Breadcrumb>

                <Flex
                    justifyContent={{ default: "justifyContentSpaceBetween" }}
                    alignItems={{ default: "alignItemsCenter" }}
                >
                    <FlexItem>
                        <Flex alignItems={{ default: "alignItemsCenter" }} spaceItems={{ default: "spaceItemsSm" }}>
                            <FlexItem>
                                <Title headingLevel="h1" size="lg">
                                    {definition.name}
                                </Title>
                            </FlexItem>
                            {definition.currentVersion !== undefined && (
                                <FlexItem>
                                    <Label color="blue">v{definition.currentVersion}</Label>
                                </FlexItem>
                            )}
                            {dirty && (
                                <FlexItem>
                                    <Label color="orange">Unsaved changes</Label>
                                </FlexItem>
                            )}
                        </Flex>
                    </FlexItem>
                    <FlexItem>
                        <Flex spaceItems={{ default: "spaceItemsSm" }}>
                            <FlexItem>
                                <Button
                                    variant="secondary"
                                    icon={<EditIcon />}
                                    onClick={handleEditMetadata}
                                >
                                    Edit Info
                                </Button>
                            </FlexItem>
                            <FlexItem>
                                <Button
                                    variant="secondary"
                                    icon={<SaveIcon />}
                                    onClick={handleSave}
                                    isDisabled={!dirty || saving}
                                    isLoading={saving}
                                >
                                    {saving ? "Saving..." : "Save"}
                                </Button>
                            </FlexItem>
                            <FlexItem>
                                <Button
                                    variant="primary"
                                    icon={<RocketIcon />}
                                    onClick={handlePublish}
                                    isDisabled={!canPublish}
                                    isLoading={publishing}
                                >
                                    {publishing ? "Publishing..." : "Publish"}
                                </Button>
                            </FlexItem>
                            <FlexItem>
                                <Button
                                    variant="danger"
                                    icon={<TrashIcon />}
                                    onClick={() => setDeleteConfirmOpen(true)}
                                >
                                    Delete
                                </Button>
                            </FlexItem>
                        </Flex>
                    </FlexItem>
                </Flex>

                {definition.description && (
                    <p className="axiom-text-subtle" style={{ marginTop: "8px" }}>
                        {definition.description}
                    </p>
                )}
            </div>

            {/* Tabbed content fills remaining space */}
            <div className="workflow-definition-detail__tabs">
                <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                    <Tab eventKey={0} title={<TabTitleText>Design</TabTitleText>} />
                    <Tab
                        eventKey={1}
                        title={<TabTitleText>Versions ({versions.length})</TabTitleText>}
                    />
                    <Tab
                        eventKey={2}
                        title={
                            <TabTitleText>
                                Runs{runsLoaded ? ` (${runsTotalCount})` : ""}
                            </TabTitleText>
                        }
                    />
                </Tabs>

                <div className="workflow-definition-detail__tab-content">
                    {activeTab === 0 && (
                        <div className="workflow-definition-detail__design">
                            {editorContent ? (
                                <WorkflowEditor
                                    workflow={editorContent}
                                    onChange={handleEditorChange}
                                    onValidationChange={handleValidationChange}
                                    theme={effectiveTheme === "dark" ? "dark" : "light"}
                                    spi={spi}
                                />
                            ) : (
                                <EmptyState>
                                    <EmptyStateBody>
                                        No workflow content. Start by adding nodes to the editor.
                                    </EmptyStateBody>
                                </EmptyState>
                            )}
                        </div>
                    )}

                    {activeTab === 1 && (
                        <div className="workflow-definition-detail__panel">
                            {versions.length === 0 ? (
                                <EmptyState>
                                    <EmptyStateBody>
                                        No published versions yet. Publish the workflow to create
                                        the first version.
                                    </EmptyStateBody>
                                </EmptyState>
                            ) : (
                                <>
                                    {versions.length < 2 ? (
                                        <EmptyState variant="xs" style={{ marginBottom: "16px" }}>
                                            <EmptyStateBody>
                                                At least two versions are required to compare.
                                            </EmptyStateBody>
                                        </EmptyState>
                                    ) : (
                                        <Toolbar>
                                            <ToolbarContent>
                                                <ToolbarItem>
                                                    <FormSelect
                                                        aria-label="Base version"
                                                        value={compareBaseVersion ?? ""}
                                                        onChange={(_e, v) => setCompareBaseVersion(Number(v))}
                                                    >
                                                        {versions.map((v) => (
                                                            <FormSelectOption
                                                                key={v.id}
                                                                value={v.version}
                                                                label={`v${v.version}`}
                                                            />
                                                        ))}
                                                    </FormSelect>
                                                </ToolbarItem>
                                                <ToolbarItem>vs</ToolbarItem>
                                                <ToolbarItem>
                                                    <FormSelect
                                                        aria-label="Compare version"
                                                        value={compareTargetVersion ?? ""}
                                                        onChange={(_e, v) => setCompareTargetVersion(Number(v))}
                                                    >
                                                        {versions.map((v) => (
                                                            <FormSelectOption
                                                                key={v.id}
                                                                value={v.version}
                                                                label={`v${v.version}`}
                                                            />
                                                        ))}
                                                    </FormSelect>
                                                </ToolbarItem>
                                                <ToolbarItem>
                                                    <Button
                                                        variant="secondary"
                                                        onClick={handleShowDiff}
                                                        isDisabled={
                                                            compareBaseVersion === null ||
                                                            compareTargetVersion === null ||
                                                            compareBaseVersion === compareTargetVersion
                                                        }
                                                    >
                                                        Show diff
                                                    </Button>
                                                </ToolbarItem>
                                            </ToolbarContent>
                                        </Toolbar>
                                    )}
                                    <Table aria-label="Workflow Versions" variant="compact">
                                        <Thead>
                                            <Tr>
                                                <Th>Version</Th>
                                                <Th>Created</Th>
                                                <Th screenReaderText="Actions" />
                                            </Tr>
                                        </Thead>
                                        <Tbody>
                                            {versions.map((v, i) => {
                                                // versions is sorted newest-first by the backend
                                                // (Sort.descending("version")), so the version
                                                // immediately before this row is the next entry.
                                                const previous = versions[i + 1];
                                                return (
                                                    <Tr key={v.id}>
                                                        <Td>
                                                            v{v.version}
                                                            {v.version === definition.currentVersion && (
                                                                <Label
                                                                    isCompact
                                                                    color="blue"
                                                                    style={{ marginLeft: "8px" }}
                                                                >
                                                                    Current
                                                                </Label>
                                                            )}
                                                        </Td>
                                                        <Td style={{ whiteSpace: "nowrap" }}>
                                                            {new Date(v.createdOn).toLocaleString()}
                                                        </Td>
                                                        <Td style={{ whiteSpace: "nowrap" }}>
                                                            <Tooltip
                                                                content={
                                                                    previous
                                                                        ? `Diff v${v.version} against v${previous.version}`
                                                                        : "No earlier version to compare against"
                                                                }
                                                            >
                                                                <Button
                                                                    variant="plain"
                                                                    aria-label={`Diff v${v.version} against previous version`}
                                                                    icon={<CodeBranchIcon />}
                                                                    isAriaDisabled={!previous}
                                                                    onClick={() =>
                                                                        previous &&
                                                                        handleDiffVsPrevious(v.version, previous.version)
                                                                    }
                                                                />
                                                            </Tooltip>
                                                        </Td>
                                                    </Tr>
                                                );
                                            })}
                                        </Tbody>
                                    </Table>
                                </>
                            )}
                        </div>
                    )}

                    {activeTab === 2 && (
                        <div className="workflow-definition-detail__panel">
                            <Toolbar>
                                <ToolbarContent>
                                    <ToolbarItem>
                                        <Button
                                            variant="control"
                                            aria-label="Refresh"
                                            onClick={loadRuns}
                                        >
                                            <SyncAltIcon />
                                        </Button>
                                    </ToolbarItem>
                                    <ToolbarItem
                                        variant="pagination"
                                        align={{ default: "alignEnd" }}
                                    >
                                        <Pagination
                                            itemCount={runsTotalCount}
                                            page={runsPage}
                                            perPage={runsPerPage}
                                            onSetPage={(_e, p) => setRunsPage(p)}
                                            onPerPageSelect={(_e, pp) => {
                                                setRunsPerPage(pp);
                                                setRunsPage(1);
                                            }}
                                            isCompact
                                        />
                                    </ToolbarItem>
                                </ToolbarContent>
                            </Toolbar>

                            {runsLoading ? (
                                <EmptyState>
                                    <EmptyStateBody>Loading workflow runs...</EmptyStateBody>
                                </EmptyState>
                            ) : runs.length === 0 ? (
                                <EmptyState>
                                    <EmptyStateBody>
                                        No runs recorded for this workflow yet.
                                    </EmptyStateBody>
                                </EmptyState>
                            ) : (
                                <Table aria-label="Workflow Runs" variant="compact">
                                    <Thead>
                                        <Tr>
                                            <Th>Status</Th>
                                            <Th>Project</Th>
                                            <Th>Version</Th>
                                            <Th>Started</Th>
                                            <Th>Duration</Th>
                                            <Th>Actions</Th>
                                        </Tr>
                                    </Thead>
                                    <Tbody>
                                        {runs.map((run) => (
                                            <Tr key={run.runId}>
                                                <Td>
                                                    <Label
                                                        isCompact
                                                        color={RUN_STATUS_COLORS[run.status] || "grey"}
                                                    >
                                                        {run.status}
                                                    </Label>
                                                </Td>
                                                <Td>
                                                    <Link to={`/projects/${run.projectId}`}>
                                                        {run.projectName || `Project #${run.projectId}`}
                                                    </Link>
                                                </Td>
                                                <Td>v{run.definitionVersion}</Td>
                                                <Td style={{ whiteSpace: "nowrap" }}>
                                                    {new Date(run.startedOn).toLocaleString()}
                                                </Td>
                                                <Td style={{ whiteSpace: "nowrap" }}>
                                                    {formatDuration(run.startedOn, run.completedOn)}
                                                </Td>
                                                <Td>
                                                    <Link to={`/logs/workflow-runs/${run.runId}`}>
                                                        View details
                                                    </Link>
                                                </Td>
                                            </Tr>
                                        ))}
                                    </Tbody>
                                </Table>
                            )}
                        </div>
                    )}
                </div>
            </div>

            {/* Edit Metadata Modal */}
            <Modal
                isOpen={editMetadataOpen}
                onClose={() => setEditMetadataOpen(false)}
                variant="small"
            >
                <ModalHeader title="Edit Workflow Definition" />
                <ModalBody>
                    <Form>
                        <FormGroup label="Name" isRequired fieldId="name">
                            <TextInput
                                id="name"
                                isRequired
                                value={metadataForm.name}
                                onChange={(_e, v) => setMetadataForm({ ...metadataForm, name: v })}
                            />
                        </FormGroup>
                        <FormGroup label="Description" fieldId="description">
                            <TextArea
                                id="description"
                                value={metadataForm.description}
                                onChange={(_e, v) => setMetadataForm({ ...metadataForm, description: v })}
                                rows={3}
                            />
                        </FormGroup>
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button
                        variant="primary"
                        onClick={handleSaveMetadata}
                        isDisabled={!metadataForm.name.trim()}
                    >
                        Save
                    </Button>
                    <Button variant="link" onClick={() => setEditMetadataOpen(false)}>
                        Cancel
                    </Button>
                </ModalFooter>
            </Modal>

            {/* Delete Confirmation Modal */}
            <ConfirmDeleteModal
                isOpen={deleteConfirmOpen}
                title="Delete Workflow Definition"
                onConfirm={handleDelete}
                onCancel={() => { setDeleteConfirmOpen(false); setDeleteError(null); }}
            >
                {deleteError && (
                    <Alert
                        variant="danger"
                        isInline
                        title={deleteError}
                        actionClose={<AlertActionCloseButton onClose={() => setDeleteError(null)} />}
                        style={{ marginBottom: "16px" }}
                    />
                )}
                Delete workflow definition &quot;{definition.name}&quot;? This will permanently
                delete the workflow, all of its versions, and all of its runs and their
                tasks. This action cannot be undone.
            </ConfirmDeleteModal>

            {/* Version Diff Modal */}
            <Modal
                isOpen={diffOpen}
                onClose={() => setDiffOpen(false)}
                variant="large"
                width="95vw"
                maxWidth="95vw"
                className="workflow-diff-modal"
            >
                <ModalHeader
                    title={
                        compareBaseVersion !== null && compareTargetVersion !== null
                            ? `Compare v${compareBaseVersion} vs v${compareTargetVersion}`
                            : "Compare Versions"
                    }
                />
                <ModalBody className="workflow-diff-modal__body">
                    {diffLoading ? (
                        <EmptyState>
                            <EmptyStateBody>Loading versions...</EmptyStateBody>
                        </EmptyState>
                    ) : diffError ? (
                        <Alert variant="danger" isInline title={diffError} />
                    ) : diffBaseWorkflow && diffCompareWorkflow ? (
                        <div style={{ flex: "1 1 0", minHeight: 0, display: "flex" }}>
                            <WorkflowDiffViewer
                                baseWorkflow={diffBaseWorkflow}
                                compareWorkflow={diffCompareWorkflow}
                                theme={effectiveTheme === "dark" ? "dark" : "light"}
                            />
                        </div>
                    ) : null}
                </ModalBody>
                <ModalFooter>
                    <Button variant="link" onClick={() => setDiffOpen(false)}>
                        Close
                    </Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}
