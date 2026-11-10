import { useState, useEffect, useCallback } from "react";
import {
    Alert,
    Button,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    HelperText,
    HelperTextItem,
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
import PencilAltIcon from "@patternfly/react-icons/dist/esm/icons/pencil-alt-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {
    type Secret,
    fetchSecrets,
    createSecret,
    updateSecret,
    deleteSecret,
    fetchSystemConfig,
} from "../config/api";
import { expiryLabel, isValidLocalDateTime, toApiInstant } from "../config/secretExpiry";
import { sseClient, type AxiomSseEvent } from "../config/sse";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";

const STATUS_ORDER = { EXPIRED: 0, EXPIRING: 1, VALID: 2, NONE: 3 } as const;

/** ISO instant to the local "YYYY-MM-DDTHH:mm" value a datetime-local input expects. */
function toLocalInput(iso?: string | null): string {
    if (!iso) return "";
    const d = new Date(iso);
    const pad = (n: number) => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function SecretsPage() {
    const [secrets, setSecrets] = useState<Secret[]>([]);
    const [loading, setLoading] = useState(true);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);
    const [editing, setEditing] = useState<Secret | null>(null);
    const [formName, setFormName] = useState("");
    const [formDescription, setFormDescription] = useState("");
    const [formValue, setFormValue] = useState("");
    const [formExpiresOn, setFormExpiresOn] = useState("");      // "YYYY-MM-DDTHH:mm" local
    const [formWarnDays, setFormWarnDays] = useState("");
    const [defaultWarnDays, setDefaultWarnDays] = useState<number>(7);
    const [saveError, setSaveError] = useState<string | null>(null);

    const load = useCallback(() => {
        setLoading(true);
        fetchSecrets().then(setSecrets).catch(console.error).finally(() => setLoading(false));
    }, []);

    useEffect(() => { load(); }, [load]);

    useEffect(() => {
        fetchSystemConfig().then((c) => setDefaultWarnDays(c.secretExpiryWarningDays ?? 7)).catch(() => {});
        return sseClient.subscribe((event: AxiomSseEvent) => {
            if (event.type === "secret-expiry") load();
        });
    }, [load]);

    const openCreate = () => {
        setEditing(null);
        setFormName("");
        setFormDescription("");
        setFormValue("");
        setFormExpiresOn("");
        setFormWarnDays("");
        setSaveError(null);
        setIsModalOpen(true);
    };

    const openEdit = (secret: Secret) => {
        setEditing(secret);
        setFormName(secret.name);
        setFormDescription(secret.description || "");
        setFormValue("");
        setFormExpiresOn(toLocalInput(secret.expiresOn));
        setFormWarnDays(secret.warnDaysBefore?.toString() ?? "");
        setSaveError(null);
        setIsModalOpen(true);
    };

    const expiresOnInvalid = formExpiresOn !== "" && !isValidLocalDateTime(formExpiresOn);
    const expiresOnInPast = !expiresOnInvalid && formExpiresOn !== ""
        && new Date(formExpiresOn).getTime() <= Date.now();
    const expiresOnError = expiresOnInvalid || expiresOnInPast;

    const sorted = [...secrets].sort((a, b) =>
        STATUS_ORDER[a.expiryStatus] - STATUS_ORDER[b.expiryStatus]
        || (a.expiresOn ?? "").localeCompare(b.expiresOn ?? "")
        || a.name.localeCompare(b.name));

    const handleSave = () => {
        setSaveError(null);
        try {
            const data = {
                name: formName,
                description: formDescription || undefined,
                value: formValue,
                expiresOn: formExpiresOn ? toApiInstant(formExpiresOn) : null,
                warnDaysBefore: !formExpiresOn || formWarnDays === "" ? null : Number(formWarnDays),
            };
            const promise = editing ? updateSecret(editing.id, data) : createSecret(data);
            promise
                .then(() => { setIsModalOpen(false); load(); })
                .catch((e: unknown) => setSaveError(e instanceof Error ? e.message : String(e)));
        } catch (e: unknown) {
            setSaveError(e instanceof Error ? e.message : String(e));
        }
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

            <p className="axiom-text-subtle" style={{ marginTop: "8px", marginBottom: "16px" }}>
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
                                <Th>Expires</Th>
                                <Th>Value</Th>
                                <Th />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {sorted.map((s) => (
                                <Tr key={s.id}>
                                    <Td><code>{s.name}</code></Td>
                                    <Td>{s.description || "—"}</Td>
                                    <Td>
                                        {s.expiryStatus === "NONE" || !s.expiresOn ? "—" : (
                                            <>
                                                {new Date(s.expiresOn).toLocaleString()}{" "}
                                                {s.expiryStatus !== "VALID" && (
                                                    <Label color={s.expiryStatus === "EXPIRED" ? "red" : "orange"}
                                                        isCompact>
                                                        {expiryLabel(s)}
                                                    </Label>
                                                )}
                                            </>
                                        )}
                                    </Td>
                                    <Td className="axiom-text-subtle">••••••••</Td>
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
                        <FormGroup label="Expires on" fieldId="expiresOn">
                            <HelperText><HelperTextItem variant={expiresOnError ? "error" : "default"}>
                                {expiresOnInvalid
                                    ? "Enter a valid date and time."
                                    : expiresOnInPast
                                    ? "This date has passed. Enter a new expiration or clear it."
                                    : "Optional. Expired secrets are not given to agents, tools, or MCP servers."}
                            </HelperTextItem></HelperText>
                            <Flex>
                                <TextInput id="expiresOn" type="datetime-local" value={formExpiresOn}
                                    validated={expiresOnError ? "error" : "default"}
                                    onChange={(_e, v) => setFormExpiresOn(v)} />
                                {formExpiresOn && (
                                    <Button variant="link" onClick={() => { setFormExpiresOn(""); setFormWarnDays(""); }}>Clear</Button>
                                )}
                            </Flex>
                        </FormGroup>
                        <FormGroup label="Warn days before" fieldId="warnDaysBefore">
                            <TextInput id="warnDaysBefore" type="number" min={0} value={formWarnDays}
                                isDisabled={!formExpiresOn}
                                onChange={(_e, v) => setFormWarnDays(v)}
                                placeholder={`Default: ${defaultWarnDays}`} />
                        </FormGroup>
                        {saveError && <Alert variant="danger" isInline title={saveError} />}
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button variant="primary" onClick={handleSave}
                        isDisabled={!formName.trim() || !formValue.trim() || expiresOnError || Number(formWarnDays) < 0}>
                        {editing ? "Update" : "Create"}
                    </Button>
                    <Button variant="link" onClick={() => setIsModalOpen(false)}>Cancel</Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}
