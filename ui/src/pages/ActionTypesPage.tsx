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
    TextInput,
    Title,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {
    type ActionType,
    type NewActionType,
    fetchActionTypes,
    createActionType,
    deleteActionType,
} from "../config/api";
import { BooleanStatusIcon } from "../components/BooleanStatusIcon";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";

export function ActionTypesPage() {
    const navigate = useNavigate();
    const [actionTypes, setActionTypes] = useState<ActionType[]>([]);
    const [loading, setLoading] = useState(true);
    const [isCreateOpen, setIsCreateOpen] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);
    const [newName, setNewName] = useState("");
    const [newMode, setNewMode] = useState("actor");

    const load = useCallback(() => {
        setLoading(true);
        fetchActionTypes().then(setActionTypes).catch(console.error).finally(() => setLoading(false));
    }, []);

    useEffect(() => { load(); }, [load]);

    const handleCreate = () => {
        const data: NewActionType = {
            name: newName,
            executionMode: newMode,
            userTriggerable: false,
            managerTriggerable: false,
            emitsEvent: false,
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
                        setNewMode("actor");
                        setIsCreateOpen(true);
                    }}>
                        Create Action Type
                    </Button>
                </FlexItem>
            </Flex>

            <div style={{ marginTop: "16px" }}>
                {loading ? (
                    <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
                ) : actionTypes.length === 0 ? (
                    <EmptyState><EmptyStateBody>No action types.</EmptyStateBody></EmptyState>
                ) : (
                    <Table aria-label="Action Types" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Name</Th>
                                <Th>Mode</Th>
                                <Th>User Triggerable</Th>
                                <Th>Manager Triggerable</Th>
                                <Th>Emits Event</Th>
                                <Th>Tools</Th>
                                <Th>Template</Th>
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
                                        <Label isCompact color={at.executionMode === "actor" ? "blue" : "orange"}>
                                            {at.executionMode}
                                        </Label>
                                    </Td>
                                    <Td><BooleanStatusIcon value={at.userTriggerable} /></Td>
                                    <Td><BooleanStatusIcon value={at.managerTriggerable} /></Td>
                                    <Td><BooleanStatusIcon value={at.emitsEvent} /></Td>
                                    <Td>{at.executionMode === "actor" ? `${at.allowedTools?.length || 0} tools` : "—"}</Td>
                                    <Td>{at.executionMode === "actor"
                                        ? (at.promptTemplate ? "Configured" : "—")
                                        : (at.scriptTemplate ? "Configured" : "—")}</Td>
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

            {/* Simple create modal — just name and mode, then navigate to detail page */}
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
                                <FormSelectOption value="actor" label="Actor — AI agent or human" />
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
