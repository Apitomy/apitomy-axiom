import { useState, useEffect, useCallback } from "react";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    HelperText,
    HelperTextItem,
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
import PencilAltIcon from "@patternfly/react-icons/dist/esm/icons/pencil-alt-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {
    type Secret,
    fetchSecrets,
    createSecret,
    updateSecret,
    deleteSecret,
} from "../config/api";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";

export function SecretsPage() {
    const [secrets, setSecrets] = useState<Secret[]>([]);
    const [loading, setLoading] = useState(true);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);
    const [editing, setEditing] = useState<Secret | null>(null);
    const [formName, setFormName] = useState("");
    const [formDescription, setFormDescription] = useState("");
    const [formValue, setFormValue] = useState("");

    const load = useCallback(() => {
        setLoading(true);
        fetchSecrets().then(setSecrets).catch(console.error).finally(() => setLoading(false));
    }, []);

    useEffect(() => { load(); }, [load]);

    const openCreate = () => {
        setEditing(null);
        setFormName("");
        setFormDescription("");
        setFormValue("");
        setIsModalOpen(true);
    };

    const openEdit = (secret: Secret) => {
        setEditing(secret);
        setFormName(secret.name);
        setFormDescription(secret.description || "");
        setFormValue("");
        setIsModalOpen(true);
    };

    const handleSave = () => {
        const data = { name: formName, description: formDescription || undefined, value: formValue };
        const promise = editing
            ? updateSecret(editing.id, data)
            : createSecret(data);
        promise
            .then(() => { setIsModalOpen(false); load(); })
            .catch(console.error);
    };

    const handleDelete = (e: React.MouseEvent, id: number) => {
        e.stopPropagation();
        setDeleteTarget(id);
    };

    const confirmDelete = () => {
        if (deleteTarget !== null) {
            deleteSecret(deleteTarget).then(load).catch(console.error);
            setDeleteTarget(null);
        }
    };

    return (
        <PageSection>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}>
                <FlexItem>
                    <Title headingLevel="h1" size="lg">Secrets</Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="primary" icon={<PlusCircleIcon />} onClick={openCreate}>
                        Add Secret
                    </Button>
                </FlexItem>
            </Flex>

            <p style={{ color: "#6a6e73", marginTop: "8px", marginBottom: "16px" }}>
                Secrets are injected as environment variables into AI agent and script
                subprocesses. Use them for CLI authentication tokens (e.g. GH_TOKEN,
                JIRA_API_TOKEN). Values are encrypted at rest and never returned by the API.
            </p>

            <div>
                {loading ? (
                    <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
                ) : secrets.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>No secrets configured.</EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Secrets" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Name</Th>
                                <Th>Description</Th>
                                <Th>Value</Th>
                                <Th />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {secrets.map((s) => (
                                <Tr key={s.id}>
                                    <Td><code>{s.name}</code></Td>
                                    <Td>{s.description || "—"}</Td>
                                    <Td style={{ color: "#6a6e73" }}>••••••••</Td>
                                    <Td>
                                        <Button variant="plain" size="sm" style={{ padding: 0 }}
                                            onClick={() => openEdit(s)} aria-label="Edit">
                                            <PencilAltIcon />
                                        </Button>
                                        <Button variant="plain" size="sm" style={{ padding: 0 }}
                                            onClick={(e) => handleDelete(e, s.id)} aria-label="Delete">
                                            <TrashIcon />
                                        </Button>
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

            <ConfirmDeleteModal isOpen={deleteTarget !== null} title="Delete Secret"
                onConfirm={confirmDelete} onCancel={() => setDeleteTarget(null)}>
                Delete this secret? This cannot be undone.
            </ConfirmDeleteModal>

            <Modal isOpen={isModalOpen} onClose={() => setIsModalOpen(false)} variant="medium">
                <ModalHeader title={editing ? "Update Secret" : "Add Secret"} />
                <ModalBody>
                    <Form>
                        <FormGroup label="Name" isRequired fieldId="name">
                            <HelperText><HelperTextItem>The environment variable name (e.g. GH_TOKEN, JIRA_API_TOKEN)</HelperTextItem></HelperText>
                            <TextInput id="name" isRequired value={formName}
                                onChange={(_e, v) => setFormName(v)}
                                isDisabled={editing !== null} />
                        </FormGroup>
                        <FormGroup label="Description" fieldId="description">
                            <TextInput id="description" value={formDescription}
                                onChange={(_e, v) => setFormDescription(v)}
                                placeholder="What this secret is used for" />
                        </FormGroup>
                        <FormGroup label="Value" isRequired fieldId="value">
                            <HelperText><HelperTextItem>{editing
                                ? "Enter the new value. The previous value cannot be displayed."
                                : "The secret value (will be encrypted at rest)"}</HelperTextItem></HelperText>
                            <TextInput id="value" type="password" isRequired value={formValue}
                                onChange={(_e, v) => setFormValue(v)}
                                placeholder={editing ? "Enter new value" : "Enter secret value"} />
                        </FormGroup>
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button variant="primary" onClick={handleSave}
                        isDisabled={!formName.trim() || !formValue.trim()}>
                        {editing ? "Update" : "Create"}
                    </Button>
                    <Button variant="link" onClick={() => setIsModalOpen(false)}>Cancel</Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}
