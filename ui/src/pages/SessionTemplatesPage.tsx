import { useState, useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import {
    Button,
    Flex,
    FlexItem,
    Label,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    PageSection,
    Spinner,
    TextInput,
    EmptyState,
    EmptyStateBody,
    EmptyStateFooter,
    Form,
    FormGroup,
} from "@patternfly/react-core";
import { Table, Thead, Tr, Th, Tbody, Td } from "@patternfly/react-table";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import CopyIcon from "@patternfly/react-icons/dist/esm/icons/copy-icon";
import RobotIcon from "@patternfly/react-icons/dist/esm/icons/robot-icon";
import {
    fetchAssistantTemplates,
    createAssistantTemplate,
    deleteAssistantTemplate,
    type SessionTemplate,
} from "../config/api";

export function SessionTemplatesPage() {
    const navigate = useNavigate();
    const [templates, setTemplates] = useState<SessionTemplate[]>([]);
    const [loading, setLoading] = useState(true);
    const [isCreateOpen, setIsCreateOpen] = useState(false);
    const [newName, setNewName] = useState("");
    const [creating, setCreating] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<string | null>(null);

    const load = useCallback(() => {
        setLoading(true);
        fetchAssistantTemplates()
            .then(setTemplates)
            .catch(console.error)
            .finally(() => setLoading(false));
    }, []);

    useEffect(() => { load(); }, [load]);

    const handleCreate = () => {
        if (!newName.trim()) return;
        setCreating(true);
        createAssistantTemplate({
            name: newName.trim(),
            description: "",
            systemPrompt: "# Assistant\n\nYou are a helpful AI assistant.",
        })
            .then((created) => {
                setIsCreateOpen(false);
                setNewName("");
                navigate(`/session-templates/${created.templateId}`);
            })
            .catch(console.error)
            .finally(() => setCreating(false));
    };

    const handleClone = (template: SessionTemplate) => {
        createAssistantTemplate({
            name: template.name + " (Copy)",
            description: template.description,
            systemPrompt: template.systemPrompt,
            welcomeMessage: template.welcomeMessage,
            workingDirectory: template.workingDirectory,
            mcpServers: template.mcpServers,
            allowedTools: template.allowedTools,
        })
            .then((created) => navigate(`/session-templates/${created.templateId}`))
            .catch(console.error);
    };

    const handleDelete = () => {
        if (!deleteTarget) return;
        deleteAssistantTemplate(deleteTarget)
            .then(() => {
                setDeleteTarget(null);
                load();
            })
            .catch(console.error);
    };

    if (loading) {
        return <PageSection><Spinner size="lg" /></PageSection>;
    }

    return (
        <PageSection>
            <Flex style={{ marginBottom: 16 }}>
                <FlexItem grow={{ default: "grow" }}>
                    <span style={{ fontSize: "24px", fontWeight: 600 }}>
                        AI Assistant Templates
                    </span>
                </FlexItem>
                <FlexItem>
                    <Button variant="primary" onClick={() => setIsCreateOpen(true)}>
                        Create Template
                    </Button>
                </FlexItem>
            </Flex>

            {templates.length === 0 ? (
                <EmptyState headingLevel="h2" icon={RobotIcon}
                    titleText="No templates">
                    <EmptyStateBody>
                        Create a session template to configure AI Assistant sessions
                        for different tasks.
                    </EmptyStateBody>
                    <EmptyStateFooter>
                        <Button variant="primary"
                            onClick={() => setIsCreateOpen(true)}>
                            Create Template
                        </Button>
                    </EmptyStateFooter>
                </EmptyState>
            ) : (
                <Table aria-label="Session templates" variant="compact">
                    <Thead>
                        <Tr>
                            <Th>Name</Th>
                            <Th>Description</Th>
                            <Th>Type</Th>
                            <Th width={15}>Actions</Th>
                        </Tr>
                    </Thead>
                    <Tbody>
                        {templates.map((t) => (
                            <Tr key={t.templateId} isClickable
                                onRowClick={() =>
                                    navigate(`/session-templates/${t.templateId}`)
                                }>
                                <Td>{t.name}</Td>
                                <Td>{t.description}</Td>
                                <Td>
                                    <Label color={t.builtIn ? "blue" : "green"}>
                                        {t.builtIn ? "Built-in" : "Custom"}
                                    </Label>
                                </Td>
                                <Td>
                                    {t.builtIn ? (
                                        <Button variant="plain" size="sm"
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                handleClone(t);
                                            }}>
                                            <CopyIcon />
                                        </Button>
                                    ) : (
                                        <Button variant="plain" size="sm"
                                            isDanger
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                setDeleteTarget(t.templateId);
                                            }}>
                                            <TrashIcon />
                                        </Button>
                                    )}
                                </Td>
                            </Tr>
                        ))}
                    </Tbody>
                </Table>
            )}

            {/* Create modal */}
            <Modal isOpen={isCreateOpen}
                onClose={() => setIsCreateOpen(false)}
                variant="small" aria-label="Create template">
                <ModalHeader title="Create Template" />
                <ModalBody>
                    <Form>
                        <FormGroup label="Name" isRequired fieldId="name">
                            <TextInput id="name" value={newName}
                                onChange={(_e, v) => setNewName(v)}
                                onKeyDown={(e) => {
                                    if (e.key === "Enter") handleCreate();
                                }} />
                        </FormGroup>
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button variant="primary" onClick={handleCreate}
                        isDisabled={!newName.trim() || creating}
                        isLoading={creating}>
                        Create
                    </Button>
                    <Button variant="link"
                        onClick={() => setIsCreateOpen(false)}>
                        Cancel
                    </Button>
                </ModalFooter>
            </Modal>

            {/* Delete confirmation */}
            <Modal isOpen={deleteTarget !== null}
                onClose={() => setDeleteTarget(null)}
                variant="small" aria-label="Confirm delete">
                <ModalHeader title="Delete Template?" />
                <ModalBody>
                    Are you sure you want to delete this template? This cannot be undone.
                </ModalBody>
                <ModalFooter>
                    <Button variant="danger" onClick={handleDelete}>Delete</Button>
                    <Button variant="link"
                        onClick={() => setDeleteTarget(null)}>Cancel</Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}
