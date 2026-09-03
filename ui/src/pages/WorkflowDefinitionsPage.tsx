import { useState, useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import {
    Alert,
    AlertActionCloseButton,
    Button,
    EmptyState,
    EmptyStateBody,
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
    Pagination,
    TextArea,
    TextInput,
    Title,
    Toolbar,
    ToolbarContent,
    ToolbarItem,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {
    type ChipFilterCriteria,
    type ChipFilterType,
    ChipFilterInput,
    FilterChips,
} from "@apitomy/common-ui-components";
import {
    type WorkflowDefinition,
    type NewWorkflowDefinition,
    fetchWorkflowDefinitions,
    createWorkflowDefinition,
    deleteWorkflowDefinition,
} from "../config/api";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";

const FILTER_TYPES: ChipFilterType[] = [
    { value: "name", label: "Name", testId: "workflow-definition-filter-name" },
];

export function WorkflowDefinitionsPage() {
    const navigate = useNavigate();
    const [workflowDefinitions, setWorkflowDefinitions] = useState<WorkflowDefinition[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [loading, setLoading] = useState(true);
    const [isCreateOpen, setIsCreateOpen] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);
    const [deleteError, setDeleteError] = useState<string | null>(null);
    const [newName, setNewName] = useState("");
    const [newDescription, setNewDescription] = useState("");

    const [filters, setFilters] = useState<ChipFilterCriteria[]>([]);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);

    const filterName = filters.find((f) => f.filterBy.value === "name")?.filterValue;
    const isFiltered = filters.length > 0;

    const load = useCallback(() => {
        setLoading(true);
        fetchWorkflowDefinitions(page, perPage, filterName || undefined)
            .then((results) => {
                setWorkflowDefinitions(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterName]);

    useEffect(() => { load(); }, [load]);

    const onAddFilterCriteria = (criteria: ChipFilterCriteria) => {
        if (!criteria.filterValue) return;
        const updated = filters.filter((f) =>
            !(f.filterBy.value === criteria.filterBy.value && f.filterValue === criteria.filterValue));
        if (criteria.filterBy.value === "name") {
            const withoutSame = updated.filter((f) => f.filterBy.value !== criteria.filterBy.value);
            withoutSame.push(criteria);
            setFilters(withoutSame);
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

    const handleCreate = () => {
        const data: NewWorkflowDefinition = {
            name: newName,
            description: newDescription || undefined,
        };
        createWorkflowDefinition(data)
            .then((created) => {
                setIsCreateOpen(false);
                setNewName("");
                setNewDescription("");
                navigate(`/components/workflows/${created.id}`);
            })
            .catch(console.error);
    };

    const handleDelete = (e: React.MouseEvent, id: number) => {
        e.stopPropagation();
        setDeleteError(null);
        setDeleteTarget(id);
    };

    const confirmDelete = () => {
        if (deleteTarget === null) {
            return;
        }
        setDeleteError(null);
        deleteWorkflowDefinition(deleteTarget)
            .then(() => {
                setDeleteTarget(null);
                load();
            })
            .catch((err) => {
                console.error("Failed to delete workflow definition:", err);
                setDeleteError("Failed to delete this workflow. Please try again.");
            });
    };

    const formatDate = (dateString: string) => {
        const date = new Date(dateString);
        return date.toLocaleDateString(undefined, {
            year: "numeric",
            month: "short",
            day: "numeric",
            hour: "2-digit",
            minute: "2-digit",
        });
    };

    const truncateDescription = (description?: string, maxLength = 100) => {
        if (!description) return "";
        if (description.length <= maxLength) return description;
        return description.substring(0, maxLength) + "...";
    };

    return (
        <PageSection>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }} alignItems={{ default: "alignItemsCenter" }}>
                <FlexItem><Title headingLevel="h1" size="lg">Workflow Definitions</Title></FlexItem>
                <FlexItem>
                    <Button variant="primary" icon={<PlusCircleIcon />} onClick={() => {
                        setNewName("");
                        setNewDescription("");
                        setIsCreateOpen(true);
                    }}>
                        Create Workflow Definition
                    </Button>
                </FlexItem>
            </Flex>

            <Toolbar style={{ marginTop: "16px" }}>
                <ToolbarContent>
                    <ToolbarItem>
                        <ChipFilterInput
                            filterTypes={FILTER_TYPES}
                            onAddCriteria={onAddFilterCriteria} />
                    </ToolbarItem>
                    <ToolbarItem>
                        <Button variant="control" aria-label="Refresh" onClick={load}>
                            <SyncAltIcon />
                        </Button>
                    </ToolbarItem>
                    <ToolbarItem variant="pagination" align={{ default: "alignEnd" }}>
                        <Pagination
                            itemCount={totalCount}
                            perPage={perPage}
                            page={page}
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
                    <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
                ) : workflowDefinitions.length === 0 ? (
                    <EmptyState><EmptyStateBody>
                        {isFiltered
                            ? "No workflow definitions match the current filters."
                            : "No workflow definitions defined."}
                    </EmptyStateBody></EmptyState>
                ) : (
                    <Table aria-label="Workflow Definitions" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Name</Th>
                                <Th>Description</Th>
                                <Th>Version</Th>
                                <Th>Updated</Th>
                                <Th />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {workflowDefinitions.map((wd) => (
                                <Tr
                                    key={wd.id}
                                    isClickable
                                    onRowClick={() => navigate(`/components/workflows/${wd.id}`)}
                                >
                                    <Td>{wd.name}</Td>
                                    <Td>{truncateDescription(wd.description)}</Td>
                                    <Td>
                                        {wd.currentVersion != null ? (
                                            <Label isCompact color="blue">
                                                v{wd.currentVersion}
                                            </Label>
                                        ) : (
                                            <Label isCompact color="grey">
                                                Draft
                                            </Label>
                                        )}
                                    </Td>
                                    <Td>{formatDate(wd.updatedOn)}</Td>
                                    <Td>
                                        <Button variant="plain" size="sm" style={{ padding: 0 }}
                                            onClick={(e) => handleDelete(e, wd.id)}>
                                            <TrashIcon />
                                        </Button>
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

            <ConfirmDeleteModal isOpen={deleteTarget !== null} title="Delete Workflow Definition"
                onConfirm={confirmDelete}
                onCancel={() => { setDeleteTarget(null); setDeleteError(null); }}>
                {deleteError && (
                    <Alert
                        variant="danger"
                        isInline
                        title={deleteError}
                        actionClose={<AlertActionCloseButton onClose={() => setDeleteError(null)} />}
                        style={{ marginBottom: "16px" }}
                    />
                )}
                Delete this workflow definition? This will permanently delete the workflow,
                all of its versions, and all of its runs and their tasks. This action cannot
                be undone.
            </ConfirmDeleteModal>

            <Modal isOpen={isCreateOpen} onClose={() => setIsCreateOpen(false)} variant="small">
                <ModalHeader title="Create Workflow Definition" />
                <ModalBody>
                    <Form>
                        <FormGroup label="Name" isRequired fieldId="name">
                            <TextInput
                                id="name"
                                isRequired
                                value={newName}
                                onChange={(_e, v) => setNewName(v)}
                                onKeyDown={(e) => {
                                    if (e.key === "Enter" && newName.trim()) {
                                        e.preventDefault();
                                        handleCreate();
                                    }
                                }}
                            />
                        </FormGroup>
                        <FormGroup label="Description" fieldId="description">
                            <TextArea
                                id="description"
                                value={newDescription}
                                onChange={(_e, v) => setNewDescription(v)}
                                rows={3}
                            />
                        </FormGroup>
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button variant="primary" onClick={handleCreate} isDisabled={!newName.trim()}>
                        Create
                    </Button>
                    <Button variant="link" onClick={() => setIsCreateOpen(false)}>
                        Cancel
                    </Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}
