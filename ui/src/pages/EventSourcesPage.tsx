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
    HelperText,
    HelperTextItem,
    FormSelectOption,
    Modal,
    ModalBody,
    ModalFooter,
    ModalHeader,
    PageSection,
    Switch,
    TextInput,
    Title,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import MinusCircleIcon from "@patternfly/react-icons/dist/esm/icons/minus-circle-icon";
import {
    type EventSource,
    type NewEventSource,
    type Secret,
    fetchEventSources,
    fetchSecrets,
    createEventSource,
    updateEventSource,
    deleteEventSource,
} from "../config/api";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";

export function EventSourcesPage() {
    const navigate = useNavigate();
    const [sources, setSources] = useState<EventSource[]>([]);
    const [loading, setLoading] = useState(true);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [editing, setEditing] = useState<EventSource | null>(null);
    const [form, setForm] = useState<NewEventSource>({
        name: "", sourceType: "github", enabled: true,
    });

    const [deleteTarget, setDeleteTarget] = useState<number | null>(null);

    const [secrets, setSecrets] = useState<Secret[]>([]);

    // GitHub-specific fields
    const [ghUrl, setGhUrl] = useState("");

    // Jira-specific fields
    const [jiraUrl, setJiraUrl] = useState("");

    const load = useCallback(() => {
        setLoading(true);
        fetchEventSources().then(setSources).catch(console.error).finally(() => setLoading(false));
    }, []);

    useEffect(() => { load(); }, [load]);
    useEffect(() => { fetchSecrets().then(setSecrets).catch(console.error); }, []);

    const parseGitHubUrl = (url: string): { owner: string; name: string; instance: string } | null => {
        try {
            const trimmed = url.replace(/\/+$/, "");
            const parsed = new URL(trimmed);
            const parts = parsed.pathname.split("/").filter(Boolean);
            if (parts.length >= 2) {
                return { instance: parsed.origin, owner: parts[0], name: parts[1] };
            }
        } catch { /* invalid URL */ }
        return null;
    };

    const parseJiraUrl = (url: string): { baseUrl: string; project: string } | null => {
        try {
            const trimmed = url.replace(/\/+$/, "");
            const parsed = new URL(trimmed);
            const parts = parsed.pathname.split("/").filter(Boolean);
            // Find "projects" anywhere in the path and take the next segment as the key
            // Supports: /projects/KEY, /browse/KEY, /jira/software/c/projects/KEY/...
            const idx = parts.findIndex((p) => p === "projects" || p === "browse");
            if (idx >= 0 && idx + 1 < parts.length) {
                return { baseUrl: parsed.origin, project: parts[idx + 1] };
            }
        } catch { /* invalid URL */ }
        return null;
    };

    const openCreate = () => {
        setEditing(null);
        setForm({ name: "", sourceType: "github", enabled: true });
        setGhUrl("");
        setJiraUrl("");
        setIsModalOpen(true);
    };

    const buildConfiguration = (): Record<string, string> => {
        if (form.sourceType === "github") {
            const parsed = parseGitHubUrl(ghUrl);
            if (parsed) {
                return { url: ghUrl.replace(/\/+$/, ""), owner: parsed.owner, name: parsed.name };
            }
            return { url: ghUrl };
        } else if (form.sourceType === "jira") {
            const parsed = parseJiraUrl(jiraUrl);
            if (parsed) {
                return { url: jiraUrl.replace(/\/+$/, ""), baseUrl: parsed.baseUrl, project: parsed.project };
            }
            return { url: jiraUrl };
        }
        return {};
    };

    const handleSave = () => {
        const data: NewEventSource = { ...form, configuration: buildConfiguration() };
        const action = editing
            ? updateEventSource(editing.id, data)
            : createEventSource(data);
        action.then(() => { setIsModalOpen(false); load(); }).catch(console.error);
    };

    const handleDelete = (id: number) => {
        setDeleteTarget(id);
    };

    const confirmDelete = () => {
        if (deleteTarget !== null) {
            deleteEventSource(deleteTarget).then(load).catch(console.error);
            setDeleteTarget(null);
        }
    };

    const handleSourceTypeChange = (newType: string) => {
        setForm({ ...form, sourceType: newType });
        setGhUrl("");
        setJiraUrl("");
    };

    const describeSource = (s: EventSource): string => {
        const config = s.configuration as Record<string, string> | undefined;
        if (!config) return "—";
        if (s.sourceType === "github") {
            return config.owner && config.name ? `${config.owner}/${config.name}` : config.url || "—";
        }
        if (s.sourceType === "jira") {
            return config.project || "—";
        }
        return "—";
    };

    const isFormValid = (): boolean => {
        if (!form.name || !form.sourceType) return false;
        if (form.sourceType === "github" && !parseGitHubUrl(ghUrl)) return false;
        if (form.sourceType === "jira" && !parseJiraUrl(jiraUrl)) return false;
        return true;
    };

    return (
        <PageSection>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}>
                <FlexItem>
                    <Title headingLevel="h1" size="lg">Event Sources</Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="primary" icon={<PlusCircleIcon />} onClick={openCreate}>
                        Add Event Source
                    </Button>
                </FlexItem>
            </Flex>

            <div style={{ marginTop: "16px" }}>
                {loading ? (
                    <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
                ) : sources.length === 0 ? (
                    <EmptyState>
                        <EmptyStateBody>No event sources configured.</EmptyStateBody>
                    </EmptyState>
                ) : (
                    <Table aria-label="Event Sources" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Name</Th>
                                <Th>Type</Th>
                                <Th>Source</Th>
                                <Th>Enabled</Th>
                                <Th>Poll Interval</Th>
                                <Th />
                            </Tr>
                        </Thead>
                        <Tbody>
                            {sources.map((s) => (
                                <Tr key={s.id} isClickable onRowClick={() => navigate(`/event-sources/${s.id}`)}>
                                    <Td>{s.name}</Td>
                                    <Td>{s.sourceType}</Td>
                                    <Td><code>{describeSource(s)}</code></Td>
                                    <Td>
                                        {s.enabled
                                            ? <CheckCircleIcon color="var(--pf-t--global--color--status--success--default)" />
                                            : <MinusCircleIcon color="var(--pf-t--global--icon--color--disabled)" />}
                                    </Td>
                                    <Td>{s.pollInterval != null ? `${s.pollInterval}s` : "—"}</Td>
                                    <Td>
                                        <Button variant="plain" size="sm" style={{ padding: 0 }}
                                            onClick={(e) => { e.stopPropagation(); handleDelete(s.id); }}>
                                            <TrashIcon />
                                        </Button>
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                )}
            </div>

            <ConfirmDeleteModal isOpen={deleteTarget !== null} title="Delete Event Source"
                onConfirm={confirmDelete} onCancel={() => setDeleteTarget(null)}>
                Delete this event source?
            </ConfirmDeleteModal>

            <Modal isOpen={isModalOpen} onClose={() => setIsModalOpen(false)} variant="medium">
                <ModalHeader title={editing ? "Edit Event Source" : "Add Event Source"} />
                <ModalBody>
                    <Form>
                        <FormGroup label="Name" isRequired fieldId="name">
                            <TextInput id="name" isRequired value={form.name}
                                onChange={(_e, v) => setForm({ ...form, name: v })}
                                placeholder="e.g. My Project Repo" />
                        </FormGroup>
                        <FormGroup label="Source Type" isRequired fieldId="sourceType">
                            <FormSelect id="sourceType" value={form.sourceType}
                                onChange={(_e, v) => handleSourceTypeChange(v)}>
                                <FormSelectOption value="github" label="GitHub" />
                                <FormSelectOption value="jira" label="Jira" />
                            </FormSelect>
                        </FormGroup>

                        {form.sourceType === "github" && (
                            <FormGroup label="Repository URL" isRequired fieldId="ghUrl">
                                <TextInput id="ghUrl" isRequired value={ghUrl}
                                    onChange={(_e, v) => setGhUrl(v)}
                                    placeholder="https://github.com/owner/repo" />
                                <HelperText><HelperTextItem>Full URL to the GitHub repository (e.g. https://github.com/Apitomy/apitomy-axiom)</HelperTextItem></HelperText>
                            </FormGroup>
                        )}

                        {form.sourceType === "jira" && (
                            <FormGroup label="Project URL" isRequired fieldId="jiraUrl">
                                <TextInput id="jiraUrl" isRequired value={jiraUrl}
                                    onChange={(_e, v) => setJiraUrl(v)}
                                    placeholder="https://jira.example.com/projects/MYPROJECT" />
                                <HelperText><HelperTextItem>Full URL to the Jira project (e.g. https://issues.redhat.com/projects/APICURIO or https://jira.example.com/jira/software/c/projects/KEY)</HelperTextItem></HelperText>
                            </FormGroup>
                        )}

                        <FormGroup fieldId="enabled">
                            <Switch id="enabled" label="Enabled — actively poll for events"
                                isChecked={form.enabled}
                                onChange={(_e, v) => setForm({ ...form, enabled: v })} />
                        </FormGroup>
                        <FormGroup label="Poll Interval (seconds)" fieldId="pollInterval">
                            <TextInput id="pollInterval" type="number"
                                value={form.pollInterval?.toString() || ""}
                                onChange={(_e, v) => setForm({ ...form, pollInterval: v ? parseInt(v) : undefined })}
                                placeholder="60" />
                        </FormGroup>
                        <FormGroup label="Authentication Secret" fieldId="secretName">
                            <FormSelect id="secretName"
                                value={form.secretName || ""}
                                onChange={(_e, v) => setForm({ ...form, secretName: v || undefined })}>
                                <FormSelectOption value="" label="Default (auto-detect)" />
                                {secrets.map((s) => (
                                    <FormSelectOption key={s.name} value={s.name} label={s.name} />
                                ))}
                            </FormSelect>
                            <HelperText><HelperTextItem>Select a secret from the Secrets store for API authentication. If not set, falls back to the default provider secret (e.g. GH_TOKEN).</HelperTextItem></HelperText>
                        </FormGroup>
                    </Form>
                </ModalBody>
                <ModalFooter>
                    <Button variant="primary" onClick={handleSave} isDisabled={!isFormValid()}>
                        Save
                    </Button>
                    <Button variant="link" onClick={() => setIsModalOpen(false)}>Cancel</Button>
                </ModalFooter>
            </Modal>
        </PageSection>
    );
}
