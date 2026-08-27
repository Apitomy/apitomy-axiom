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
    FormSelect,
    FormSelectOption,
    Label,
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
import {
    type ChipFilterCriteria,
    type ChipFilterType,
    ChipFilterInput,
    FilterChips,
} from "@apitomy/common-ui-components";
import {
    type ActionType,
    type NewActionType,
    fetchActionTypes,
    createActionType,
    deleteActionType,
} from "../config/api";
import { BooleanStatusIcon } from "../components/BooleanStatusIcon";
import { ColoredLabel } from "../components/ColoredLabel";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";

// Default templates sent when creating an action type so the POST is complete and
// passes server-side validation. Users refine these on the detail page afterward.
// Placeholders must be ones the server's ActionTypeValidator recognizes.
const DEFAULT_PROMPT_TEMPLATE = "Complete the following task:\n\n{{input}}\n";
const DEFAULT_SCRIPT_TEMPLATE =
    "#!/usr/bin/env bash\nset -euo pipefail\n\n# TODO: implement this action\necho \"Running action for {{projectName}}\"\n";

const FILTER_TYPES: ChipFilterType[] = [
    { value: "name", label: "Name", testId: "action-type-filter-name" },
    { value: "mode", label: "Mode", testId: "action-type-filter-mode" },
    { value: "labels", label: "Labels", testId: "action-type-filter-labels" },
];

export function ActionTypesPage() {
    const navigate = useNavigate();
    const [actionTypes, setActionTypes] = useState<ActionType[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [loading, setLoading] = useState(true);
    const [isCreateOpen, setIsCreateOpen] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);
    const [newName, setNewName] = useState("");
    const [newMode, setNewMode] = useState("agent");

    const [filters, setFilters] = useState<ChipFilterCriteria[]>([]);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);

    const filterName = filters.find((f) => f.filterBy.value === "name")?.filterValue;
    const filterMode = filters.find((f) => f.filterBy.value === "mode")?.filterValue;
    const filterLabels = filters
        .filter((f) => f.filterBy.value === "labels")
        .map((f) => f.filterValue)
        .join(",");
    const isFiltered = filters.length > 0;

    const load = useCallback(() => {
        setLoading(true);
        fetchActionTypes(page, perPage, filterName || undefined, filterMode || undefined, filterLabels || undefined)
            .then((results) => {
                setActionTypes(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage, filterName, filterMode, filterLabels]);

    useEffect(() => { load(); }, [load]);

    const onAddFilterCriteria = (criteria: ChipFilterCriteria) => {
        if (!criteria.filterValue) return;
        const updated = filters.filter((f) =>
            !(f.filterBy.value === criteria.filterBy.value && f.filterValue === criteria.filterValue));
        if (criteria.filterBy.value === "name" || criteria.filterBy.value === "mode") {
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

    const addLabelFilter = (label: string) => {
        const already = filters.some((f) => f.filterBy.value === "labels" && f.filterValue === label);
        if (!already) {
            const labelType = FILTER_TYPES.find((t) => t.value === "labels")!;
            setFilters([...filters, { filterBy: labelType, filterValue: label }]);
            setPage(1);
        }
    };

    const addModeFilter = (mode: string) => {
        const modeType = FILTER_TYPES.find((t) => t.value === "mode")!;
        const withoutMode = filters.filter((f) => f.filterBy.value !== "mode");
        withoutMode.push({ filterBy: modeType, filterValue: mode });
        setFilters(withoutMode);
        setPage(1);
    };

    const handleCreate = () => {
        const data: NewActionType = {
            name: newName,
            executionMode: newMode,
            userTriggerable: false,
            managerTriggerable: false,
            emitsEvent: false,
            promptTemplate: newMode === "agent" ? DEFAULT_PROMPT_TEMPLATE : undefined,
            scriptTemplate: newMode === "script" ? DEFAULT_SCRIPT_TEMPLATE : undefined,
        };
        createActionType(data)
            .then((created) => {
                setIsCreateOpen(false);
                setNewName("");
                navigate(`/action-types/${created.id}`);
            })
            .catch(console.error);
    };

    const handleDelete = (e: React.MouseEvent, id: number) => {
        e.stopPropagation();
        setDeleteTarget(id);
    };

    const confirmDelete = () => {
        if (deleteTarget !== null) {
            deleteActionType(deleteTarget).then(load).catch(console.error);
            setDeleteTarget(null);
        }
    };

    return (
        <PageSection>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }} alignItems={{ default: "alignItemsCenter" }}>
                <FlexItem><Title headingLevel="h1" size="lg">Action Types</Title></FlexItem>
                <FlexItem>
                    <Button variant="primary" icon={<PlusCircleIcon />} onClick={() => {
                        setNewName("");
                        setNewMode("agent");
                        setIsCreateOpen(true);
                    }}>
                        Create Action Type
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
                ) : actionTypes.length === 0 ? (
                    <EmptyState><EmptyStateBody>
                        {isFiltered
                            ? "No action types match the current filters."
                            : "No action types defined."}
                    </EmptyStateBody></EmptyState>
                ) : (
                    <Table aria-label="Action Types" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Name</Th>
                                <Th>Mode</Th>
                                <Th>Labels</Th>
                                <Th>User Triggerable</Th>
                                <Th>Manager Triggerable</Th>
                                <Th>Emits Event</Th>
                                <Th>Tools</Th>
                                <Th />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {actionTypes.map((at) => (
                                <Tr
                                    key={at.id}
                                    isClickable
                                    onRowClick={() => navigate(`/action-types/${at.id}`)}
                                >
                                    <Td>{at.name}</Td>
                                    <Td>
                                        <Label isCompact
                                            color={at.executionMode === "agent" ? "blue" : "orange"}
                                            style={{ cursor: "pointer" }}
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                addModeFilter(at.executionMode);
                                            }}>
                                            {at.executionMode}
                                        </Label>
                                    </Td>
                                    <Td>
                                        {at.labels?.map((label) => (
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
                                    <Td><BooleanStatusIcon value={at.userTriggerable} /></Td>
                                    <Td><BooleanStatusIcon value={at.managerTriggerable} /></Td>
                                    <Td><BooleanStatusIcon value={at.emitsEvent} /></Td>
                                    <Td>{at.executionMode === "agent" ? `${at.allowedTools?.length || 0} tools` : "—"}</Td>
                                    <Td>
                                        <Button variant="plain" size="sm" style={{ padding: 0 }}
                                            onClick={(e) => handleDelete(e, at.id)}>
                                            <TrashIcon />
                                        </Button>
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

            <ConfirmDeleteModal isOpen={deleteTarget !== null} title="Delete Action Type"
                onConfirm={confirmDelete} onCancel={() => setDeleteTarget(null)}>
                Delete this action type?
            </ConfirmDeleteModal>

            <Modal isOpen={isCreateOpen} onClose={() => setIsCreateOpen(false)} variant="small">
                <ModalHeader title="Create Action Type" />
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
                        <FormGroup label="Execution Mode" isRequired fieldId="executionMode">
                            <FormSelect
                                id="executionMode"
                                value={newMode}
                                onChange={(_e, v) => setNewMode(v)}
                            >
                                <FormSelectOption value="agent" label="Agent — AI agent" />
                                <FormSelectOption value="script" label="Script — bash script" />
                            </FormSelect>
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
