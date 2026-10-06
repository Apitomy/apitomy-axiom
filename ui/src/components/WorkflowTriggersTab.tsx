import { useState, useEffect, useCallback } from "react";
import {
    Alert,
    Button,
    Checkbox,
    EmptyState,
    EmptyStateBody,
    Form,
    FormGroup,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    TextArea,
    TextInput,
    Toolbar,
    ToolbarContent,
    ToolbarItem,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import {
    type NewScheduleTrigger,
    type ScheduleTrigger,
    createScheduleTrigger,
    deleteScheduleTrigger,
    fetchScheduleTriggers,
    updateScheduleTrigger,
} from "../config/api";
import { ConfirmDeleteModal } from "./ConfirmDeleteModal";

function formatTime(value?: string): string {
    return value ? new Date(value).toLocaleString() : "—";
}

/**
 * Lists the schedule triggers of a workflow definition and lets the user add, edit and delete them.
 *
 * @param definitionId the workflow definition whose triggers are managed
 */
export function WorkflowTriggersTab({ definitionId }: { definitionId: number }) {
    const [triggers, setTriggers] = useState<ScheduleTrigger[]>([]);
    const [loading, setLoading] = useState(true);
    const [editing, setEditing] = useState<ScheduleTrigger | null>(null);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [deleting, setDeleting] = useState<ScheduleTrigger | null>(null);

    const load = useCallback(() => {
        setLoading(true);
        fetchScheduleTriggers(definitionId)
            .then(setTriggers)
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [definitionId]);

    useEffect(() => { load(); }, [load]);

    const openModal = (trigger: ScheduleTrigger | null) => {
        setEditing(trigger);
        setIsModalOpen(true);
    };

    return (
        <div>
            <Toolbar>
                <ToolbarContent>
                    <ToolbarItem>
                        <Button variant="primary" onClick={() => openModal(null)}>Add trigger</Button>
                    </ToolbarItem>
                </ToolbarContent>
            </Toolbar>
            {loading ? (
                <EmptyState><EmptyStateBody>Loading triggers...</EmptyStateBody></EmptyState>
            ) : triggers.length === 0 ? (
                <EmptyState><EmptyStateBody>No schedule triggers.</EmptyStateBody></EmptyState>
            ) : (
                <Table aria-label="Schedule triggers" variant="compact">
                    <Thead>
                        <Tr>
                            <Th>Cron</Th>
                            <Th>Subject</Th>
                            <Th>Enabled</Th>
                            <Th>Next run</Th>
                            <Th>Last run</Th>
                            <Th>Last outcome</Th>
                            <Th />
                        </Tr>
                    </Thead>
                    <Tbody>
                        {triggers.map((t) => (
                            <Tr key={t.id}>
                                <Td><code>{t.cron}</code></Td>
                                <Td>{t.subjectName}</Td>
                                <Td>{t.enabled ? "Yes" : "No"}</Td>
                                <Td>{formatTime(t.nextRunAt)}</Td>
                                <Td>{formatTime(t.lastRunAt)}</Td>
                                <Td>{t.lastOutcome || "—"}</Td>
                                <Td>
                                    <Button variant="link" isInline onClick={() => openModal(t)}>Edit</Button>{" "}
                                    <Button variant="link" isInline isDanger onClick={() => setDeleting(t)}>
                                        Delete
                                    </Button>
                                </Td>
                            </Tr>
                        ))}
                    </Tbody>
                </Table>
            )}

            {isModalOpen && (
                <TriggerModal
                    trigger={editing}
                    onClose={() => setIsModalOpen(false)}
                    onSave={async (data) => {
                        if (editing) {
                            await updateScheduleTrigger(definitionId, editing.id, data);
                        } else {
                            await createScheduleTrigger(definitionId, data);
                        }
                        setIsModalOpen(false);
                        load();
                    }}
                />
            )}

            <ConfirmDeleteModal isOpen={deleting != null} title="Delete Trigger"
                onConfirm={() => {
                    if (!deleting) return;
                    deleteScheduleTrigger(definitionId, deleting.id)
                        .then(() => { setDeleting(null); load(); })
                        .catch(console.error);
                }}
                onCancel={() => setDeleting(null)}>
                Delete the schedule trigger <code>{deleting?.cron}</code>?
            </ConfirmDeleteModal>
        </div>
    );
}

function TriggerModal({ trigger, onClose, onSave }: {
    trigger: ScheduleTrigger | null;
    onClose: () => void;
    onSave: (data: NewScheduleTrigger) => Promise<void>;
}) {
    const [cron, setCron] = useState(trigger?.cron ?? "");
    const [subjectName, setSubjectName] = useState(trigger?.subjectName ?? "");
    const [inputs, setInputs] = useState(JSON.stringify(trigger?.inputs ?? {}, null, 2));
    const [enabled, setEnabled] = useState(trigger?.enabled ?? true);
    const [error, setError] = useState("");
    const [saving, setSaving] = useState(false);

    const save = () => {
        let parsed: Record<string, unknown>;
        try {
            parsed = inputs.trim() ? JSON.parse(inputs) : {};
        } catch {
            setError("Inputs must be valid JSON.");
            return;
        }
        if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
            setError("Inputs must be a JSON object.");
            return;
        }
        setError("");
        setSaving(true);
        onSave({ cron: cron.trim(), subjectName: subjectName.trim(), inputs: parsed, enabled })
            .catch((e: Error) => setError(e.message))
            .finally(() => setSaving(false));
    };

    return (
        <Modal isOpen onClose={onClose} variant="medium">
            <ModalHeader title={trigger ? "Edit trigger" : "Add trigger"} />
            <ModalBody>
                {error && <Alert variant="danger" isInline title={error} style={{ marginBottom: "12px" }} />}
                <Form>
                    <FormGroup label="Cron" isRequired fieldId="trigger-cron">
                        <TextInput id="trigger-cron" value={cron} placeholder="0 2 * * *"
                            onChange={(_e, v) => setCron(v)} />
                    </FormGroup>
                    <FormGroup label="Subject name" isRequired fieldId="trigger-subject">
                        <TextInput id="trigger-subject" value={subjectName} maxLength={246}
                            onChange={(_e, v) => setSubjectName(v)} />
                    </FormGroup>
                    <FormGroup label="Inputs (JSON)" fieldId="trigger-inputs">
                        <TextArea id="trigger-inputs" value={inputs} rows={6}
                            onChange={(_e, v) => setInputs(v)} />
                    </FormGroup>
                    <Checkbox id="trigger-enabled" label="Enabled" isChecked={enabled}
                        onChange={(_e, v) => setEnabled(v)} />
                </Form>
            </ModalBody>
            <ModalFooter>
                <Button variant="primary" onClick={save} isLoading={saving}
                    isDisabled={saving || !cron.trim() || !subjectName.trim()}>
                    Save
                </Button>
                <Button variant="link" onClick={onClose}>Cancel</Button>
            </ModalFooter>
        </Modal>
    );
}
