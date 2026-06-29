import { useState, useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    PageSection,
    TextInput,
    Title,
    Form,
    FormGroup,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {
    type Toolset,
    fetchToolsets,
    createToolset,
    deleteToolset,
} from "../config/api";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";

export function ToolsetsPage() {
    const navigate = useNavigate();
    const [toolsets, setToolsets] = useState<Toolset[]>([]);
    const [loading, setLoading] = useState(true);
    const [isCreateOpen, setIsCreateOpen] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);
    const [newName, setNewName] = useState("");

    const load = useCallback(() => {
        setLoading(true);
        fetchToolsets().then(setToolsets).catch(console.error).finally(() => setLoading(false));
    }, []);

    useEffect(() => { load(); }, [load]);

    const handleCreate = () => {
        createToolset({ name: newName, tools: [] })
            .then((created) => {
                setIsCreateOpen(false);
                setNewName("");
                navigate(`/toolsets/${created.id}`);
            })
            .catch(console.error);
    };

    const handleDelete = (e: React.MouseEvent, id: number) => {
        e.stopPropagation();
        setDeleteTarget(id);
    };

    const confirmDelete = () => {
        if (deleteTarget !== null) {
            deleteToolset(deleteTarget).then(load).catch(console.error);
            setDeleteTarget(null);
        }
    };

    return (
        <PageSection>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}>
                <FlexItem>
                    <Title headingLevel="h1" size="lg">Toolsets</Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="primary" icon={<PlusCircleIcon />}
                        onClick={() => { setNewName(""); setIsCreateOpen(true); }}>
                        Add Toolset
                    </Button>
                </FlexItem>
            </Flex>

            <div style={{ marginTop: "16px" }}>
                {loading ? (
                    <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
                ) : toolsets.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>No toolsets configured.</EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Toolsets" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Name</Th>
                                <Th>Description</Th>
                                <Th>Tools</Th>
                                <Th />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {toolsets.map((ts) => (
                                <Tr key={ts.id} isClickable
                                    onRowClick={() => navigate(`/toolsets/${ts.id}`)}>
                                    <Td>{ts.name}</Td>
                                    <Td>{ts.description || "—"}</Td>
                                    <Td>{ts.tools.length}</Td>
                                    <Td>
                                        <Button variant="plain" size="sm" style={{ padding: 0 }}
                                            onClick={(e) => handleDelete(e, ts.id)}>
                                            <TrashIcon />
                                        </Button>
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

            <ConfirmDeleteModal isOpen={deleteTarget !== null} title="Delete Toolset"
                onConfirm={confirmDelete} onCancel={() => setDeleteTarget(null)}>
                Delete this toolset?
            </ConfirmDeleteModal>

            <Modal isOpen={isCreateOpen} onClose={() => setIsCreateOpen(false)} variant="medium">
                <ModalHeader title="Add Toolset" />
                <ModalBody>
                    <Form>
                        <FormGroup label="Name" isRequired fieldId="name">
                            <TextInput id="name" isRequired value={newName}
                                onChange={(_e, v) => setNewName(v)} />
                        </FormGroup>
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button variant="primary" onClick={handleCreate} isDisabled={!newName.trim()}>
                        Create
                    </Button>
                    <Button variant="link" onClick={() => setIsCreateOpen(false)}>Cancel</Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}
