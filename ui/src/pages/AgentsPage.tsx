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
    type Agent,
    type NewAgent,
    fetchAgents,
    createAgent,
    updateAgent,
    deleteAgent,
} from "../config/api";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";

export function AgentsPage() {
    const navigate = useNavigate();
    const [agents, setAgents] = useState<Agent[]>([]);
    const [loading, setLoading] = useState(true);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);
    const [editing, setEditing] = useState<Agent | null>(null);
    const [form, setForm] = useState<NewAgent>({
        name: "", agentType: "claude-code", capabilities: [],
    });
    const [capabilitiesText, setCapabilitiesText] = useState("");

    const load = useCallback(() => {
        setLoading(true);
        fetchAgents().then(setAgents).catch(console.error).finally(() => setLoading(false));
    }, []);

    useEffect(() => { load(); }, [load]);

    const openCreate = () => {
        setEditing(null);
        setForm({ name: "", agentType: "claude-code", capabilities: [] });
        setCapabilitiesText("");
        setIsModalOpen(true);
    };

    const handleSave = () => {
        const caps = capabilitiesText.split(",").map((s) => s.trim()).filter(Boolean);
        const data = { ...form, capabilities: caps };
        const action = editing ? updateAgent(editing.id, data) : createAgent(data);
        action.then(() => { setIsModalOpen(false); load(); }).catch(console.error);
    };

    const handleDelete = (id: number) => {
        setDeleteTarget(id);
    };

    const confirmDelete = () => {
        if (deleteTarget !== null) {
            deleteAgent(deleteTarget).then(load).catch(console.error);
            setDeleteTarget(null);
        }
    };

    return (
        <PageSection>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }} alignItems={{ default: "alignItemsCenter" }}>
                <FlexItem><Title headingLevel="h1" size="lg">Agents</Title></FlexItem>
                <FlexItem><Button variant="primary" icon={<PlusCircleIcon />} onClick={openCreate}>Create Agent</Button></FlexItem>
            </Flex>

            <div style={{ marginTop: "16px" }}>
                {loading ? (
                    <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
                ) : agents.length === 0 ? (
                    <EmptyState><EmptyStateBody>No agents configured.</EmptyStateBody></EmptyState>
                ) : (
                    <Table aria-label="Agents" variant="compact">
                        <Thead><Tr><Th>Name</Th><Th>Type</Th><Th>Description</Th><Th>Capabilities</Th><Th /></Tr></Thead>
                        <Tbody>
                            {agents.map((a) => (
                                <Tr key={a.id} isClickable
                                    onRowClick={() => navigate(`/agents/${a.id}`)}>
                                    <Td>{a.name}</Td>
                                    <Td><Label isCompact color="blue">{a.agentType}</Label></Td>
                                    <Td>{a.description || "—"}</Td>
                                    <Td>{a.capabilities?.join(", ") || "—"}</Td>
                                    <Td>
                                        <Button variant="plain" size="sm" style={{ padding: 0 }}
                                            onClick={() => handleDelete(a.id)}><TrashIcon /></Button>
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

            <ConfirmDeleteModal isOpen={deleteTarget !== null} title="Delete Agent"
                onConfirm={confirmDelete} onCancel={() => setDeleteTarget(null)}>
                Delete this agent?
            </ConfirmDeleteModal>

            <Modal isOpen={isModalOpen} onClose={() => setIsModalOpen(false)} variant="medium">
                <ModalHeader title={editing ? "Edit Agent" : "Create Agent"} />
                <ModalBody>
                    <Form>
                        <FormGroup label="Name" isRequired fieldId="name">
                            <TextInput id="name" isRequired value={form.name} onChange={(_e, v) => setForm({ ...form, name: v })} />
                        </FormGroup>
                        <FormGroup label="Description" fieldId="description">
                            <TextInput id="description" value={form.description || ""} onChange={(_e, v) => setForm({ ...form, description: v })} />
                        </FormGroup>
                        <FormGroup label="Type" isRequired fieldId="agentType">
                            <FormSelect id="agentType" value={form.agentType} onChange={(_e, v) => setForm({ ...form, agentType: v })}>
                                <FormSelectOption value="claude-code" label="Claude Code" />
                                <FormSelectOption value="opencode" label="OpenCode" />
                                <FormSelectOption value="copilot" label="Copilot" />
                            </FormSelect>
                        </FormGroup>
                        <FormGroup label="Capabilities" fieldId="capabilities">
                            <TextInput id="capabilities" value={capabilitiesText} onChange={(_e, v) => setCapabilitiesText(v)} placeholder="analyze, implement, review (comma-separated)" />
                        </FormGroup>
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button variant="primary" onClick={handleSave} isDisabled={!form.name}>Save</Button>
                    <Button variant="link" onClick={() => setIsModalOpen(false)}>Cancel</Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}
