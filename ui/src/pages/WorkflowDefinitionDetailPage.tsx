import { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { useEffectiveTheme } from "../hooks/useTheme";
import { useParams, Link, useNavigate } from "react-router-dom";
import {
    Breadcrumb,
    BreadcrumbItem,
    Button,
    EmptyState,
    EmptyStateBody,
    ExpandableSection,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    Label,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    PageSection,
    TextArea,
    TextInput,
    Title,
    DescriptionList,
    DescriptionListGroup,
    DescriptionListTerm,
    DescriptionListDescription,
} from "@patternfly/react-core";
import { WorkflowEditor } from "@apitomy/flow-ui";
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
    fetchActionTypes,
} from "../config/api";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import RocketIcon from "@patternfly/react-icons/dist/esm/icons/rocket-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import EditIcon from "@patternfly/react-icons/dist/esm/icons/edit-icon";

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
    const [versionsExpanded, setVersionsExpanded] = useState(false);
    const [editMetadataOpen, setEditMetadataOpen] = useState(false);
    const [metadataForm, setMetadataForm] = useState({ name: "", description: "" });
    const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false);

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
        deleteWorkflowDefinition(id)
            .then(() => {
                navigate("/components/workflows");
            })
            .catch((err) => {
                console.error("Failed to delete workflow definition:", err);
            });
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

                {/* Version history */}
                {versions.length > 0 && (
                    <ExpandableSection
                        toggleText={`Version History (${versions.length})`}
                        isExpanded={versionsExpanded}
                        onToggle={() => setVersionsExpanded(!versionsExpanded)}
                        style={{ marginTop: "16px" }}
                    >
                        <DescriptionList isCompact isHorizontal>
                            {versions.map((v) => (
                                <DescriptionListGroup key={v.id}>
                                    <DescriptionListTerm>Version {v.version}</DescriptionListTerm>
                                    <DescriptionListDescription>
                                        {new Date(v.createdOn).toLocaleString()}
                                    </DescriptionListDescription>
                                </DescriptionListGroup>
                            ))}
                        </DescriptionList>
                    </ExpandableSection>
                )}
            </div>

            {/* Editor fills remaining space */}
            <div style={{ flex: 1, minHeight: 0 }}>
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
                onCancel={() => setDeleteConfirmOpen(false)}
            >
                Delete workflow definition &quot;{definition.name}&quot;? This action cannot be undone.
            </ConfirmDeleteModal>
        </PageSection>
    );
}
