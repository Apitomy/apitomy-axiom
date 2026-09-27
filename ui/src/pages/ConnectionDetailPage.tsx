import { useState, useEffect, useCallback, useRef } from "react";
import { useParams, Link } from "react-router-dom";
import {
    Breadcrumb,
    BreadcrumbItem,
    Button,
    DescriptionList,
    DescriptionListDescription,
    DescriptionListGroup,
    DescriptionListTerm,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    FormSelect,
    FormSelectOption,
    HelperText,
    HelperTextItem,
    Label,
    PageSection,
    Switch,
    Tab,
    TabContent,
    TabTitleText,
    Tabs,
    TextArea,
    TextInput,
    Title,
    Modal,
    ModalBody,
    ModalHeader,
    Pagination,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";
import { BooleanStatusIcon } from "../components/BooleanStatusIcon";
import {
    type Connection,
    type NewConnection,
    type ConnectionStatus,
    type ConnectionPollLog,
    type Secret,
    type StreamEvent,
    fetchConnection,
    updateConnection,
    fetchConnectionStatus,
    fetchConnectionPollLogs,
    fetchSecrets,
    fetchStreamEvents,
} from "../config/api";

const SOURCE_COLORS: Record<string, "blue" | "green" | "orange" | "grey"> = {
    github: "blue",
    jira: "green",
};

export function ConnectionDetailPage() {
    const { connectionId } = useParams<{ connectionId: string }>();
    const [connection, setConnection] = useState<Connection | null>(null);
    const [form, setForm] = useState<Partial<Connection>>({});
    const [reposText, setReposText] = useState("");
    const [projectsText, setProjectsText] = useState("");
    const [secrets, setSecrets] = useState<Secret[]>([]);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [activeTab, setActiveTab] = useState(0);

    const loadData = useCallback(() => {
        if (!connectionId) return;
        setLoading(true);
        Promise.all([fetchConnection(connectionId), fetchSecrets()])
            .then(([conn, secs]) => {
                setConnection(conn);
                setForm({
                    id: conn.id,
                    name: conn.name,
                    description: conn.description,
                    sourceType: conn.sourceType,
                    enabled: conn.enabled,
                    baseUrl: conn.baseUrl,
                    pollInterval: conn.pollInterval,
                    secretName: conn.secretName,
                    configuration: conn.configuration,
                });
                // Parse configuration for repos/projects
                const config = conn.configuration as Record<string, unknown> | undefined;
                if (conn.sourceType === "github" && config?.repositories) {
                    const repos = config.repositories as string[];
                    setReposText(Array.isArray(repos) ? repos.join("\n") : "");
                } else {
                    setReposText("");
                }
                if (conn.sourceType === "jira" && config?.projects) {
                    const projects = config.projects as string[];
                    setProjectsText(Array.isArray(projects) ? projects.join("\n") : "");
                } else {
                    setProjectsText("");
                }
                setSecrets(secs);
                setDirty(false);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [connectionId]);

    useEffect(() => { loadData(); }, [loadData]);

    const updateForm = (updates: Partial<Connection>) => {
        setForm((prev) => ({ ...prev, ...updates }));
        setDirty(true);
    };

    const handleSave = () => {
        if (!connectionId) return;
        setSaving(true);

        // Build configuration from textarea lines
        const config: Record<string, unknown> = { ...(form.configuration || {}) };
        if (form.sourceType === "github") {
            config.repositories = reposText
                .split("\n")
                .map((l) => l.trim())
                .filter(Boolean);
        }
        if (form.sourceType === "jira") {
            config.projects = projectsText
                .split("\n")
                .map((l) => l.trim())
                .filter(Boolean);
        }

        const data: NewConnection = {
            id: form.id || connectionId,
            name: form.name || "",
            description: form.description,
            sourceType: form.sourceType || "",
            enabled: form.enabled || false,
            baseUrl: form.baseUrl || "",
            secretName: form.secretName,
            pollInterval: form.pollInterval,
            configuration: config,
        };
        updateConnection(connectionId, data)
            .then((updated) => { setConnection(updated); setDirty(false); })
            .catch(console.error)
            .finally(() => setSaving(false));
    };

    if (loading) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    if (!connection) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Connection not found.</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: "16px" }}>
                <BreadcrumbItem><Link to="/events/connections">Connections</Link></BreadcrumbItem>
                <BreadcrumbItem isActive>{connection?.name || connectionId}</BreadcrumbItem>
            </Breadcrumb>

            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}>
                <FlexItem>
                    <Title headingLevel="h1" size="lg">{connection.name}</Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="primary" icon={<SaveIcon />} onClick={handleSave}
                        isDisabled={!dirty || !form.name || saving} isLoading={saving}>
                        {saving ? "Saving..." : "Save Changes"}
                    </Button>
                </FlexItem>
            </Flex>

            <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                <Tab eventKey={0} title={<TabTitleText>Info</TabTitleText>}>
                    <TabContent id="info-tab" eventKey={0} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <InfoTab
                            form={form}
                            updateForm={updateForm}
                            reposText={reposText}
                            setReposText={(v) => { setReposText(v); setDirty(true); }}
                            projectsText={projectsText}
                            setProjectsText={(v) => { setProjectsText(v); setDirty(true); }}
                            secrets={secrets}
                        />
                    </TabContent>
                </Tab>
                <Tab eventKey={1} title={<TabTitleText>Status</TabTitleText>}>
                    <TabContent id="status-tab" eventKey={1} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <StatusTab connectionId={connectionId!} isActive={activeTab === 1} />
                    </TabContent>
                </Tab>
                <Tab eventKey={2} title={<TabTitleText>Poll Logs</TabTitleText>}>
                    <TabContent id="poll-logs-tab" eventKey={2} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <PollLogsTab connectionId={connectionId!} isActive={activeTab === 2} />
                    </TabContent>
                </Tab>
            </Tabs>
        </PageSection>
    );
}

function InfoTab({ form, updateForm, reposText, setReposText, projectsText, setProjectsText, secrets }: {
    form: Partial<Connection>;
    updateForm: (updates: Partial<Connection>) => void;
    reposText: string;
    setReposText: (v: string) => void;
    projectsText: string;
    setProjectsText: (v: string) => void;
    secrets: Secret[];
}) {
    return (
        <Form style={{ maxWidth: "600px" }}>
            <FormGroup label="ID" fieldId="id">
                <TextInput id="id" value={form.id || ""} isDisabled />
                <HelperText><HelperTextItem>Connection ID cannot be changed after creation.</HelperTextItem></HelperText>
            </FormGroup>
            <FormGroup label="Name" isRequired fieldId="name">
                <TextInput id="name" isRequired value={form.name || ""}
                    onChange={(_e, v) => updateForm({ name: v })} />
            </FormGroup>
            <FormGroup label="Description" fieldId="description">
                <TextArea id="description" value={form.description || ""}
                    onChange={(_e, v) => updateForm({ description: v })} />
            </FormGroup>
            <FormGroup label="Source Type" fieldId="sourceType">
                <div>
                    <Label color={SOURCE_COLORS[form.sourceType || ""] || "grey"}>
                        {form.sourceType || "unknown"}
                    </Label>
                </div>
            </FormGroup>
            <FormGroup label="Base URL" isRequired fieldId="baseUrl">
                <TextInput id="baseUrl" isRequired value={form.baseUrl || ""}
                    onChange={(_e, v) => updateForm({ baseUrl: v })}
                    placeholder="https://github.com" />
            </FormGroup>
            {form.sourceType === "github" && (
                <FormGroup label="Repositories" fieldId="repositories">
                    <TextArea id="repositories" value={reposText}
                        onChange={(_e, v) => setReposText(v)}
                        placeholder="owner/repo (one per line)"
                        rows={5} />
                    <HelperText><HelperTextItem>One repository per line (e.g. owner/repo).</HelperTextItem></HelperText>
                </FormGroup>
            )}
            {form.sourceType === "jira" && (
                <FormGroup label="Projects" fieldId="projects">
                    <TextArea id="projects" value={projectsText}
                        onChange={(_e, v) => setProjectsText(v)}
                        placeholder="PROJECT_KEY (one per line)"
                        rows={5} />
                    <HelperText><HelperTextItem>One Jira project key per line.</HelperTextItem></HelperText>
                </FormGroup>
            )}
            <FormGroup fieldId="enabled">
                <Switch id="enabled" label="Enabled — actively poll for events"
                    isChecked={form.enabled || false}
                    onChange={(_e, v) => updateForm({ enabled: v })} />
            </FormGroup>
            <FormGroup label="Poll Interval (seconds)" fieldId="pollInterval">
                <TextInput id="pollInterval" type="number"
                    value={form.pollInterval?.toString() || ""}
                    onChange={(_e, v) => updateForm({ pollInterval: v ? parseInt(v) : undefined })}
                    placeholder="60" />
            </FormGroup>
            <FormGroup label="Authentication Secret" fieldId="secretName">
                <FormSelect id="secretName"
                    value={form.secretName || ""}
                    onChange={(_e, v) => updateForm({ secretName: v || undefined })}>
                    <FormSelectOption value="" label="Default (auto-detect)" />
                    {secrets.map((s) => (
                        <FormSelectOption key={s.name} value={s.name} label={s.name} />
                    ))}
                </FormSelect>
                <HelperText><HelperTextItem>Select a secret for API authentication. Falls back to the default provider secret if not set.</HelperTextItem></HelperText>
            </FormGroup>
        </Form>
    );
}

function StatusTab({ connectionId, isActive }: {
    connectionId: string;
    isActive: boolean;
}) {
    const [status, setStatus] = useState<ConnectionStatus | null>(null);
    const [events, setEvents] = useState<StreamEvent[]>([]);
    const [loading, setLoading] = useState(false);
    const [eventsLoading, setEventsLoading] = useState(false);
    const loadedRef = useRef(false);

    const loadStatus = useCallback(() => {
        setLoading(true);
        fetchConnectionStatus(connectionId)
            .then(setStatus)
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [connectionId]);

    const loadEvents = useCallback(() => {
        setEventsLoading(true);
        fetchStreamEvents(1, 10, undefined, connectionId)
            .then((results) => setEvents(results.items))
            .catch(console.error)
            .finally(() => setEventsLoading(false));
    }, [connectionId]);

    useEffect(() => {
        if (isActive && !loadedRef.current) {
            loadedRef.current = true;
            loadStatus();
            loadEvents();
        }
    }, [isActive, loadStatus, loadEvents]);

    if (loading) {
        return (
            <EmptyState><EmptyStateBody>Loading status...</EmptyStateBody></EmptyState>
        );
    }

    return (
        <div>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}>
                <FlexItem>
                    <Title headingLevel="h3" size="md">Connection Status</Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="plain" aria-label="Refresh" onClick={() => {
                        loadStatus();
                        loadEvents();
                    }}>
                        <SyncAltIcon />
                    </Button>
                </FlexItem>
            </Flex>

            {status && (
                <DescriptionList isHorizontal style={{ marginBottom: "32px" }}>
                    <DescriptionListGroup>
                        <DescriptionListTerm>Enabled</DescriptionListTerm>
                        <DescriptionListDescription>
                            <BooleanStatusIcon value={status.enabled} />
                        </DescriptionListDescription>
                    </DescriptionListGroup>
                    <DescriptionListGroup>
                        <DescriptionListTerm>Last Polled At</DescriptionListTerm>
                        <DescriptionListDescription>
                            {status.lastPolledAt
                                ? new Date(status.lastPolledAt).toLocaleString()
                                : "Never"}
                        </DescriptionListDescription>
                    </DescriptionListGroup>
                    <DescriptionListGroup>
                        <DescriptionListTerm>Total Events Produced</DescriptionListTerm>
                        <DescriptionListDescription>
                            {status.totalEventsProduced}
                        </DescriptionListDescription>
                    </DescriptionListGroup>
                    <DescriptionListGroup>
                        <DescriptionListTerm>Last Error</DescriptionListTerm>
                        <DescriptionListDescription>
                            {status.lastError
                                ? status.lastError
                                : <span><CheckCircleIcon color="var(--pf-t--global--color--status--success--default)" /> None</span>}
                        </DescriptionListDescription>
                    </DescriptionListGroup>
                    <DescriptionListGroup>
                        <DescriptionListTerm>Last Error At</DescriptionListTerm>
                        <DescriptionListDescription>
                            {status.lastErrorAt
                                ? new Date(status.lastErrorAt).toLocaleString()
                                : "—"}
                        </DescriptionListDescription>
                    </DescriptionListGroup>
                </DescriptionList>
            )}

            <Title headingLevel="h3" size="md" style={{ marginBottom: "16px" }}>Recent Events</Title>

            {eventsLoading ? (
                <EmptyState>
                    <EmptyStateBody>Loading events...</EmptyStateBody>
                </EmptyState>
            ) : events.length === 0 ? (
                <EmptyState>
                    <EmptyStateBody>No events yet</EmptyStateBody>
                </EmptyState>
            ) : (
                <Table aria-label="Recent Events" variant="compact">
                    <Thead>
                        <Tr>
                            <Th>Time</Th>
                            <Th>Type</Th>
                            <Th>Ref</Th>
                        </Tr>
                    </Thead>
                    <Tbody>
                        {events.map((event) => (
                            <Tr key={event.id}>
                                <Td style={{ whiteSpace: "nowrap" }}>
                                    {new Date(event.timestamp).toLocaleString()}
                                </Td>
                                <Td>{event.type}</Td>
                                <Td>{event.ref.length > 60 ? event.ref.substring(0, 57) + "..." : event.ref}</Td>
                            </Tr>
                        ))}
                    </Tbody>
                </Table>
            )}
        </div>
    );
}

function formatDuration(ms?: number): string {
    if (ms == null) return "-";
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
}

function PollLogsTab({ connectionId, isActive }: {
    connectionId: string;
    isActive: boolean;
}) {
    const [logs, setLogs] = useState<ConnectionPollLog[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [loading, setLoading] = useState(false);
    const [detailModal, setDetailModal] = useState<ConnectionPollLog | null>(null);
    const loadedRef = useRef(false);

    const loadLogs = useCallback(() => {
        setLoading(true);
        fetchConnectionPollLogs(connectionId, page, perPage)
            .then((results) => {
                setLogs(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [connectionId, page, perPage]);

    useEffect(() => {
        if (isActive && !loadedRef.current) {
            loadedRef.current = true;
            loadLogs();
        }
    }, [isActive, loadLogs]);

    useEffect(() => {
        if (loadedRef.current) {
            loadLogs();
        }
    }, [page, perPage, loadLogs]);

    return (
        <div>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}>
                <FlexItem>
                    <Title headingLevel="h3" size="md">Poll Logs</Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="plain" aria-label="Refresh" onClick={loadLogs}>
                        <SyncAltIcon />
                    </Button>
                </FlexItem>
            </Flex>

            {loading ? (
                <EmptyState>
                    <EmptyStateBody>Loading poll logs...</EmptyStateBody>
                </EmptyState>
            ) : logs.length === 0 ? (
                <EmptyState>
                    <EmptyStateBody>No poll logs yet. Logs will appear after the connection is polled.</EmptyStateBody>
                </EmptyState>
            ) : (
                <>
                    <Table aria-label="Poll Logs" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Status</Th>
                                <Th>Time</Th>
                                <Th>Message</Th>
                                <Th>Events</Th>
                                <Th>Duration</Th>
                            </Tr>
                        </Thead>
                        <Tbody>
                            {logs.map((log) => (
                                <Tr key={log.id}
                                    isClickable={!!log.detail}
                                    onRowClick={() => log.detail && setDetailModal(log)}>
                                    <Td>
                                        <Label color={log.status === "success" ? "green" : "red"}
                                            icon={log.status === "success"
                                                ? <CheckCircleIcon />
                                                : <ExclamationCircleIcon />}>
                                            {log.status}
                                        </Label>
                                    </Td>
                                    <Td style={{ whiteSpace: "nowrap" }}>
                                        {log.createdOn
                                            ? new Date(log.createdOn).toLocaleString()
                                            : "-"}
                                    </Td>
                                    <Td>{log.message}</Td>
                                    <Td>{log.eventsIngested ?? "-"}</Td>
                                    <Td>{formatDuration(log.durationMs)}</Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>

                    <Pagination
                        itemCount={totalCount}
                        page={page}
                        perPage={perPage}
                        onSetPage={(_e, p) => setPage(p)}
                        onPerPageSelect={(_e, pp) => { setPerPage(pp); setPage(1); }}
                        variant="bottom"
                    />
                </>
            )}

            {detailModal && (
                <Modal
                    isOpen
                    onClose={() => setDetailModal(null)}
                    variant="medium">
                    <ModalHeader title="Poll Log Detail" />
                    <ModalBody>
                        <DescriptionList isHorizontal>
                            <DescriptionListGroup>
                                <DescriptionListTerm>Status</DescriptionListTerm>
                                <DescriptionListDescription>
                                    <Label color={detailModal.status === "success" ? "green" : "red"}>
                                        {detailModal.status}
                                    </Label>
                                </DescriptionListDescription>
                            </DescriptionListGroup>
                            <DescriptionListGroup>
                                <DescriptionListTerm>Message</DescriptionListTerm>
                                <DescriptionListDescription>{detailModal.message}</DescriptionListDescription>
                            </DescriptionListGroup>
                            <DescriptionListGroup>
                                <DescriptionListTerm>Detail</DescriptionListTerm>
                                <DescriptionListDescription>
                                    <pre style={{ whiteSpace: "pre-wrap", wordBreak: "break-word" }}>
                                        {detailModal.detail}
                                    </pre>
                                </DescriptionListDescription>
                            </DescriptionListGroup>
                            <DescriptionListGroup>
                                <DescriptionListTerm>Events Ingested</DescriptionListTerm>
                                <DescriptionListDescription>{detailModal.eventsIngested ?? "-"}</DescriptionListDescription>
                            </DescriptionListGroup>
                            <DescriptionListGroup>
                                <DescriptionListTerm>Duration</DescriptionListTerm>
                                <DescriptionListDescription>{formatDuration(detailModal.durationMs)}</DescriptionListDescription>
                            </DescriptionListGroup>
                            <DescriptionListGroup>
                                <DescriptionListTerm>Time</DescriptionListTerm>
                                <DescriptionListDescription>
                                    {detailModal.createdOn
                                        ? new Date(detailModal.createdOn).toLocaleString()
                                        : "-"}
                                </DescriptionListDescription>
                            </DescriptionListGroup>
                        </DescriptionList>
                    </ModalBody>
                </Modal>
            )}
        </div>
    );
}
