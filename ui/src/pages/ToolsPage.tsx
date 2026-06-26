import { useState, useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    PageSection,
    Pagination,
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
import { ColoredLabel } from "../components/ColoredLabel";
import {
    type ChipFilterCriteria,
    type ChipFilterType,
    ChipFilterInput,
    FilterChips,
} from "@apitomy/common-ui-components";
import {
    type ToolDefinition,
    type NewToolDefinition,
    fetchTools,
    createTool,
    deleteTool,
} from "../config/api";

const FILTER_TYPES: ChipFilterType[] = [
    { value: "name", label: "Name", testId: "tool-filter-name" },
    { value: "labels", label: "Labels", testId: "tool-filter-labels" },
];

export function ToolsPage() {
    const navigate = useNavigate();
    const [tools, setTools] = useState<ToolDefinition[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [loading, setLoading] = useState(true);
    const [isCreateOpen, setIsCreateOpen] = useState(false);
    const [newName, setNewName] = useState("");

    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);

    const [filters, setFilters] = useState<ChipFilterCriteria[]>([]);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);

    const filterName = filters.find((f) => f.filterBy.value === "name")?.filterValue;
    const filterLabels = filters
        .filter((f) => f.filterBy.value === "labels")
        .map((f) => f.filterValue)
        .join(",");
    const isFiltered = filters.length > 0;

    const load = useCallback(() => {
        setLoading(true);
        fetchTools(page, perPage, filterName || undefined, filterLabels || undefined)
            .then((results) => {
                setTools(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterName, filterLabels]);

    useEffect(() => { load(); }, [load]);

    const onAddFilterCriteria = (criteria: ChipFilterCriteria) => {
        if (!criteria.filterValue) return;
        const updated = filters.filter((f) =>
            !(f.filterBy.value === criteria.filterBy.value && f.filterValue === criteria.filterValue));
        if (criteria.filterBy.value === "name") {
            const withoutName = updated.filter((f) => f.filterBy.value !== "name");
            withoutName.push(criteria);
            setFilters(withoutName);
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

    const addLabelFilter = (label: string) => {
        const already = filters.some((f) => f.filterBy.value === "labels" && f.filterValue === label);
        if (!already) {
            const labelType = FILTER_TYPES.find((t) => t.value === "labels")!;
            setFilters([...filters, { filterBy: labelType, filterValue: label }]);
            setPage(1);
        }
    };

    const handleCreate = () => {
        const data: NewToolDefinition = {
            name: newName,
        };
        createTool(data)
            .then((created) => {
                setIsCreateOpen(false);
                setNewName("");
                navigate(`/tools/${created.id}`);
            })
            .catch(console.error);
    };

    const handleDelete = (e: React.MouseEvent, id: number) => {
        e.stopPropagation();
        setDeleteTarget(id);
    };

    const confirmDelete = () => {
        if (deleteTarget !== null) {
            deleteTool(deleteTarget).then(load).catch(console.error);
            setDeleteTarget(null);
        }
    };

    return (
        <PageSection>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }} alignItems={{ default: "alignItemsCenter" }}>
                <FlexItem><Title headingLevel="h1" size="lg">Tools</Title></FlexItem>
                <FlexItem>
                    <Button variant="primary" icon={<PlusCircleIcon />} onClick={() => {
                        setNewName("");
                        setIsCreateOpen(true);
                    }}>
                        Create Tool
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
                ) : tools.length === 0 ? (
                    <EmptyState><EmptyStateBody>
                        {isFiltered
                            ? "No tools match the current filters."
                            : "No tools defined. Create a tool to provide custom capabilities to AI agents."}
                    </EmptyStateBody></EmptyState>
                ) : (
                    <>
                        <Table aria-label="Tools" variant="compact">
                            <Thead>
                                <Tr>
                                    <Th>Name</Th>
                                    <Th>Description</Th>
                                    <Th>Labels</Th>
                                    <Th />
                                </Tr>
                            </Thead>
                            <Tbody>
                                {tools.map((tool) => (
                                    <Tr
                                        key={tool.id}
                                        isClickable
                                        onRowClick={() => navigate(`/tools/${tool.id}`)}
                                    >
                                        <Td style={{ whiteSpace: "nowrap" }}>{tool.name}</Td>
                                        <Td>{tool.description || "—"}</Td>
                                        <Td>
                                            {tool.labels?.map((label) => (
                                                <ColoredLabel key={label} isCompact
                                                    style={{ marginRight: "4px", cursor: "pointer" }}
                                                    onClick={(e) => {
                                                        e.stopPropagation();
                                                        addLabelFilter(label);
                                                    }}>
                                                    {label}
                                                </ColoredLabel>
                                            ))}
                                        </Td>
                                        <Td>
                                            <Button variant="plain" size="sm" style={{ padding: 0 }}
                                                onClick={(e) => handleDelete(e, tool.id)}>
                                                <TrashIcon />
                                            </Button>
                                        </Td>
                                    </Tr>
                                ))}
                            </Tbody>
                        </Table>
                    </>
                )}
            </div>

            <Modal isOpen={deleteTarget !== null} onClose={() => setDeleteTarget(null)} variant="small">
                <ModalHeader title="Delete Tool" />
                <ModalBody>
                    Delete this tool?
                </ModalBody>
                <ModalFooter>
                    <Button variant="danger" onClick={confirmDelete}>
                        Delete
                    </Button>
                    <Button variant="link" onClick={() => setDeleteTarget(null)}>
                        Cancel
                    </Button>
                </ModalFooter>
            </Modal>

            <Modal isOpen={isCreateOpen} onClose={() => setIsCreateOpen(false)} variant="small">
                <ModalHeader title="Create Tool" />
                <ModalBody>
                    <Form>
                        <FormGroup label="Name" isRequired fieldId="name">
                            <TextInput
                                id="name"
                                isRequired
                                value={newName}
                                onChange={(_e, v) => setNewName(v)}
                                placeholder="e.g. post_github_comment"
                                onKeyDown={(e) => {
                                    if (e.key === "Enter" && newName.trim()) {
                                        e.preventDefault();
                                        handleCreate();
                                    }
                                }}
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
