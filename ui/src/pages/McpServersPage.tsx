import { useState, useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import {
    Alert,
    Button,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Label,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    PageSection,
    TextInput,
    Title,
    Form,
    FormGroup,
    Radio,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {
    type McpServer,
    fetchMcpServers,
    createMcpServer,
    deleteMcpServer,
} from "../config/api";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";

export function McpServersPage() {
    const navigate = useNavigate();
    const [servers, setServers] = useState<McpServer[]>([]);
    const [loading, setLoading] = useState(true);
    const [isCreateOpen, setIsCreateOpen] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);
    const [newName, setNewName] = useState("");
    const [newTransport, setNewTransport] = useState<"stdio" | "http">("stdio");
    const [newTarget, setNewTarget] = useState("");
    const [createError, setCreateError] = useState<string | null>(null);

    const openCreate = () => {
        setNewName("");
        setNewTransport("stdio");
        setNewTarget("");
        setCreateError(null);
        setIsCreateOpen(true);
    };

    const load = useCallback(() => {
        setLoading(true);
        fetchMcpServers().then(setServers).catch(console.error).finally(() => setLoading(false));
    }, []);

    useEffect(() => { load(); }, [load]);

    const handleCreate = () => {
        const target = newTarget.trim();
        const server = newTransport === "stdio"
            ? { name: newName.trim(), serverCommand: target }
            : { name: newName.trim(), serverUrl: target };
        setCreateError(null);
        createMcpServer(server)
            .then((created) => {
                setIsCreateOpen(false);
                navigate(`/mcp-servers/${created.id}`);
            })
            .catch((e: Error) => setCreateError(e.message));
    };

    const handleDelete = (e: React.MouseEvent, id: number) => {
        e.stopPropagation();
        setDeleteTarget(id);
    };

    const confirmDelete = () => {
        if (deleteTarget !== null) {
            deleteMcpServer(deleteTarget).then(load).catch(console.error);
            setDeleteTarget(null);
        }
    };

    return (
        <PageSection>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}>
                <FlexItem>
                    <Title headingLevel="h1" size="lg">MCP Servers</Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="primary" icon={<PlusCircleIcon />}
                        onClick={openCreate}>
                        Add MCP Server
                    </Button>
                </FlexItem>
            </Flex>

            <div style={{ marginTop: "16px" }}>
                {loading ? (
                    <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
                ) : servers.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>No MCP servers registered.</EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="MCP Servers" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Name</Th>
                                <Th>Description</Th>
                                <Th>Transport</Th>
                                <Th />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {servers.map((s) => (
                                <Tr key={s.id} isClickable
                                    onRowClick={() => navigate(`/mcp-servers/${s.id}`)}>
                                    <Td>{s.name}</Td>
                                    <Td>{s.description || "—"}</Td>
                                    <Td>
                                        <Label isCompact color={s.serverUrl ? "blue" : "green"}>
                                            {s.serverUrl ? "HTTP" : s.serverCommand ? "stdio" : "—"}
                                        </Label>
                                    </Td>
                                    <Td>
                                        <Button variant="plain" size="sm" style={{ padding: 0 }}
                                            onClick={(e) => handleDelete(e, s.id)}>
                                            <TrashIcon />
                                        </Button>
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

            <ConfirmDeleteModal isOpen={deleteTarget !== null} title="Delete MCP Server"
                onConfirm={confirmDelete} onCancel={() => setDeleteTarget(null)}>
                Delete this MCP server?
            </ConfirmDeleteModal>

            <Modal isOpen={isCreateOpen} onClose={() => setIsCreateOpen(false)} variant="medium">
                <ModalHeader title="Add MCP Server" />
                <ModalBody>
                    <Form>
                        {createError && <Alert variant="danger" isInline title={createError} />}
                        <FormGroup label="Name" isRequired fieldId="name">
                            <TextInput id="name" isRequired value={newName}
                                onChange={(_e, v) => setNewName(v)} />
                        </FormGroup>
                        <FormGroup label="Transport" isRequired role="radiogroup" fieldId="transport" isInline>
                            <Radio id="transport-stdio" name="transport" label="stdio (local command)"
                                isChecked={newTransport === "stdio"} onChange={() => setNewTransport("stdio")} />
                            <Radio id="transport-http" name="transport" label="HTTP (remote URL)"
                                isChecked={newTransport === "http"} onChange={() => setNewTransport("http")} />
                        </FormGroup>
                        {newTransport === "stdio" ? (
                            <FormGroup label="Command" isRequired fieldId="new-command">
                                <TextInput id="new-command" isRequired value={newTarget}
                                    onChange={(_e, v) => setNewTarget(v)} placeholder="e.g. npx, python3, node" />
                            </FormGroup>
                        ) : (
                            <FormGroup label="URL" isRequired fieldId="new-url">
                                <TextInput id="new-url" isRequired value={newTarget}
                                    onChange={(_e, v) => setNewTarget(v)} placeholder="https://mcp.example.com/mcp" />
                            </FormGroup>
                        )}
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button variant="primary" onClick={handleCreate} isDisabled={!newName.trim() || !newTarget.trim()}>
                        Create
                    </Button>
                    <Button variant="link" onClick={() => setIsCreateOpen(false)}>Cancel</Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}
