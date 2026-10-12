import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import {
    Alert,
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
    TextArea,
    TextInput,
    Title,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {
    type Composition,
    addItem,
    createComposition,
    deleteComposition,
    listCompositions,
} from "../config/compositions";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";

/**
 * Lists workbench compositions and lets the user create (seeded with a blank workflow) or delete them.
 */
export function WorkbenchListPage() {
    const navigate = useNavigate();
    const [items, setItems] = useState<Composition[]>([]);
    const [loading, setLoading] = useState(true);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [name, setName] = useState("");
    const [goal, setGoal] = useState("");
    const [creating, setCreating] = useState(false);
    /** Synchronous in-flight guard: state alone can't stop a second click before re-render. */
    const creatingRef = useRef<boolean>(false);
    const [error, setError] = useState<string | null>(null);
    const [deleteTarget, setDeleteTarget] = useState<Composition | null>(null);

    const load = useCallback(() => {
        setLoading(true);
        listCompositions().then(setItems).catch(console.error).finally(() => setLoading(false));
    }, []);

    useEffect(() => { load(); }, [load]);

    const openCreate = () => {
        setName("");
        setGoal("");
        setError(null);
        setIsModalOpen(true);
    };

    const handleCreate = async () => {
        const trimmed = name.trim();
        if (!trimmed || creatingRef.current) {
            return;
        }
        creatingRef.current = true;
        setCreating(true);
        setError(null);
        try {
            const created = await createComposition({ name: trimmed, goal: goal.trim() || undefined });
            await addItem(created.id, { type: "workflow", name: trimmed });
            setIsModalOpen(false);
            navigate(`/workbench/${created.id}`);
        } catch (e) {
            setError(e instanceof Error ? e.message : String(e));
        } finally {
            creatingRef.current = false;
            setCreating(false);
        }
    };

    const confirmDelete = () => {
        if (deleteTarget) {
            deleteComposition(deleteTarget.id).then(load).catch(console.error);
            setDeleteTarget(null);
        }
    };

    return (
        <PageSection>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }} alignItems={{ default: "alignItemsCenter" }}>
                <FlexItem><Title headingLevel="h1" size="lg">Workbench</Title></FlexItem>
                <FlexItem>
                    <Button variant="primary" icon={<PlusCircleIcon />} onClick={openCreate}>New composition</Button>
                </FlexItem>
            </Flex>
            <p className="axiom-text-subtle" style={{ marginTop: "8px" }}>
                Compositions group workflows, action types and tools that you build together with the AI assistant.
            </p>

            <div style={{ marginTop: "16px" }}>
                {loading ? (
                    <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
                ) : items.length === 0 ? (
                    <EmptyState titleText="No compositions" headingLevel="h2">
                        <EmptyStateBody>Create a composition to start building in the workbench.</EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Compositions" variant="compact">
                        <Thead><Tr><Th>Name</Th><Th>Goal</Th><Th>State</Th><Th>Updated</Th><Th screenReaderText="Actions" /></Tr></Thead>
                        <Tbody>
                            {items.map((c) => (
                                <Tr key={c.id}>
                                    <Td dataLabel="Name"><Link to={`/workbench/${c.id}`}>{c.name}</Link></Td>
                                    <Td dataLabel="Goal">{c.goal || "—"}</Td>
                                    <Td dataLabel="State">
                                        {c.turnState === "ai-working"
                                            ? <Label isCompact color="purple">AI working</Label>
                                            : <Label isCompact>idle</Label>}
                                    </Td>
                                    <Td dataLabel="Updated">{c.updatedAt ? new Date(c.updatedAt).toLocaleString() : "—"}</Td>
                                    <Td>
                                        <Button variant="plain" size="sm" style={{ padding: 0 }}
                                            aria-label={`Delete ${c.name}`}
                                            onClick={() => setDeleteTarget(c)}><TrashIcon /></Button>
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

            <ConfirmDeleteModal isOpen={deleteTarget !== null} title="Delete composition"
                onConfirm={confirmDelete} onCancel={() => setDeleteTarget(null)}>
                Delete composition "{deleteTarget?.name}"?
            </ConfirmDeleteModal>

            <Modal isOpen={isModalOpen} onClose={() => setIsModalOpen(false)} variant="medium"
                aria-labelledby="new-composition-title">
                <ModalHeader title="New composition" labelId="new-composition-title" />
                <ModalBody>
                    {error && <Alert variant="danger" isInline title={error} style={{ marginBottom: "16px" }} />}
                    <Form onSubmit={(e) => { e.preventDefault(); void handleCreate(); }}>
                        <FormGroup label="Name" isRequired fieldId="composition-name">
                            <TextInput id="composition-name" isRequired autoFocus value={name} onChange={(_e, v) => setName(v)} />
                        </FormGroup>
                        <FormGroup label="Goal" fieldId="composition-goal">
                            <TextArea id="composition-goal" value={goal} onChange={(_e, v) => setGoal(v)} />
                        </FormGroup>
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button variant="primary" onClick={() => void handleCreate()}
                        isDisabled={!name.trim() || creating} isLoading={creating}>Create</Button>
                    <Button variant="link" onClick={() => setIsModalOpen(false)}>Cancel</Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}
