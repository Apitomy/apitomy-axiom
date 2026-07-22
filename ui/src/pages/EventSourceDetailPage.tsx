import { useState, useEffect, useCallback } from "react";
import { useParams, Link } from "react-router-dom";
import {
    Breadcrumb,
    BreadcrumbItem,
    Button,
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
    CodeBlock,
    CodeBlockCode,
    Label,
    Modal,
    ModalBody,
    ModalHeader,
    PageSection,
    Pagination,
    Switch,
    Tab,
    TabContent,
    TabTitleText,
    Tabs,
    TextInput,
    Title,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import {
    type EventSource,
    type EventSourceLog,
    type Secret,
    fetchEventSource,
    updateEventSource,
    fetchEventSourceLogs,
    fetchSecrets,
} from "../config/api";

export function EventSourceDetailPage() {
    const { eventSourceId } = useParams<{ eventSourceId: string }>();
    const id = Number(eventSourceId);

    const [source, setSource] = useState<EventSource | null>(null);
    const [form, setForm] = useState<Partial<EventSource>>({});
    const [sourceUrl, setSourceUrl] = useState("");
    const [secrets, setSecrets] = useState<Secret[]>([]);
    const [logs, setLogs] = useState<EventSourceLog[]>([]);
    const [logsTotalCount, setLogsTotalCount] = useState(0);
    const [logsPage, setLogsPage] = useState(1);
    const [logsPerPage, setLogsPerPage] = useState(20);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [activeTab, setActiveTab] = useState(0);

    const loadData = useCallback(() => {
        if (!id) return;
        setLoading(true);
        Promise.all([fetchEventSource(id), fetchSecrets(), fetchEventSourceLogs(id, 1, 20)])
            .then(([src, secs, logResults]) => {
                setSource(src);
                setForm({
                    name: src.name,
                    description: src.description,
                    sourceType: src.sourceType,
                    enabled: src.enabled,
                    pollInterval: src.pollInterval,
                    secretName: src.secretName,
                    configuration: src.configuration,
                });
                setSourceUrl(buildUrlFromConfig(src));
                setSecrets(secs);
                setLogs(logResults.items);
                setLogsTotalCount(logResults.totalCount);
                setLogsPage(1);
                setDirty(false);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [id]);

    useEffect(() => { loadData(); }, [loadData]);

    const updateForm = (updates: Partial<EventSource>) => {
        setForm((prev) => ({ ...prev, ...updates }));
        setDirty(true);
    };

    const handleSave = () => {
        setSaving(true);
        const config = buildConfigFromUrl(form.sourceType || "", sourceUrl);
        const data = { ...form, configuration: config } as EventSource;
        updateEventSource(id, data)
            .then((updated) => { setSource(updated); setDirty(false); })
            .catch(console.error)
            .finally(() => setSaving(false));
    };

    const loadLogs = useCallback((p?: number, pp?: number) => {
        const pg = p ?? logsPage;
        const sz = pp ?? logsPerPage;
        fetchEventSourceLogs(id, pg, sz)
            .then((results) => {
                setLogs(results.items);
                setLogsTotalCount(results.totalCount);
            })
            .catch(console.error);
    }, [id, logsPage, logsPerPage]);

    if (loading) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    if (!source) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Event source not found.</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: "16px" }}>
                <BreadcrumbItem><Link to="/event-sources">Event Sources</Link></BreadcrumbItem>
                <BreadcrumbItem isActive>{source.name}</BreadcrumbItem>
            </Breadcrumb>

            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}>
                <FlexItem>
                    <Title headingLevel="h1" size="lg">{source.name}</Title>
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
                        <InfoTab form={form} updateForm={updateForm}
                            sourceUrl={sourceUrl} setSourceUrl={(v) => { setSourceUrl(v); setDirty(true); }}
                            secrets={secrets} />
                    </TabContent>
                </Tab>
                <Tab eventKey={1} title={<TabTitleText>
                    Poll Logs{logs.length > 0 ? ` (${logs.length})` : ""}
                </TabTitleText>}>
                    <TabContent id="logs-tab" eventKey={1} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <LogsTab logs={logs} totalCount={logsTotalCount}
                            page={logsPage} perPage={logsPerPage}
                            onPageChange={(p) => { setLogsPage(p); loadLogs(p); }}
                            onPerPageChange={(pp) => { setLogsPerPage(pp); setLogsPage(1); loadLogs(1, pp); }}
                            onRefresh={() => loadLogs()} />
                    </TabContent>
                </Tab>
            </Tabs>
        </PageSection>
    );
}

function InfoTab({ form, updateForm, sourceUrl, setSourceUrl, secrets }: {
    form: Partial<EventSource>;
    updateForm: (updates: Partial<EventSource>) => void;
    sourceUrl: string;
    setSourceUrl: (v: string) => void;
    secrets: Secret[];
}) {
    return (
        <Form style={{ maxWidth: "600px" }}>
            <FormGroup label="Name" isRequired fieldId="name">
                <TextInput id="name" isRequired value={form.name || ""}
                    onChange={(_e, v) => updateForm({ name: v })} />
            </FormGroup>
            <FormGroup label="Source Type" fieldId="sourceType">
                <TextInput id="sourceType" value={form.sourceType || ""} isDisabled />
            </FormGroup>
            {form.sourceType === "github" && (
                <FormGroup label="Repository URL" isRequired fieldId="url">
                    <TextInput id="url" isRequired value={sourceUrl}
                        onChange={(_e, v) => setSourceUrl(v)}
                        placeholder="https://github.com/owner/repo" />
                    <HelperText><HelperTextItem>Full URL to the GitHub repository</HelperTextItem></HelperText>
                </FormGroup>
            )}
            {form.sourceType === "jira" && (
                <FormGroup label="Project URL" isRequired fieldId="url">
                    <TextInput id="url" isRequired value={sourceUrl}
                        onChange={(_e, v) => setSourceUrl(v)}
                        placeholder="https://jira.example.com/projects/MYPROJECT" />
                    <HelperText><HelperTextItem>Full URL to the Jira Cloud project</HelperTextItem></HelperText>
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

function LogsTab({ logs, totalCount, page, perPage, onPageChange, onPerPageChange, onRefresh }: {
    logs: EventSourceLog[];
    totalCount: number;
    page: number;
    perPage: number;
    onPageChange: (page: number) => void;
    onPerPageChange: (perPage: number) => void;
    onRefresh: () => void;
}) {
    const [selectedLog, setSelectedLog] = useState<EventSourceLog | null>(null);

    return (
        <div>
            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}>
                <FlexItem>
                    <p className="axiom-text-subtle">
                        Poll activity for this event source. Click a row to view details.
                    </p>
                </FlexItem>
                <FlexItem>
                    <Button variant="plain" aria-label="Refresh" onClick={onRefresh}>
                        <SyncAltIcon />
                    </Button>
                </FlexItem>
            </Flex>

            {logs.length === 0 ? (
                <EmptyState>
                    <EmptyStateBody>No poll logs yet. Logs will appear after the first poll cycle.</EmptyStateBody>
                </EmptyState>
            ) : (
                <>
                    <Table aria-label="Poll Logs" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Status</Th>
                                <Th>Message</Th>
                                <Th>Events</Th>
                                <Th>Time</Th>
                            </Tr>
                        </Thead>
                        <Tbody>
                            {logs.map((log) => (
                                <Tr key={log.id} isClickable
                                    onRowClick={() => setSelectedLog(log)}>
                                    <Td>
                                        <Label isCompact color={log.status === "success" ? "green" : "red"}>
                                            {log.status}
                                        </Label>
                                    </Td>
                                    <Td>{log.message}</Td>
                                    <Td>{log.eventsIngested ?? "—"}</Td>
                                    <Td style={{ whiteSpace: "nowrap" }}>
                                        {new Date(log.createdOn).toLocaleString()}
                                    </Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                    <Pagination
                        itemCount={totalCount}
                        page={page}
                        perPage={perPage}
                        onSetPage={(_e, p) => onPageChange(p)}
                        onPerPageSelect={(_e, pp) => onPerPageChange(pp)}
                        variant="bottom"
                        style={{ marginTop: "8px" }}
                    />
                </>
            )}

            <Modal isOpen={selectedLog !== null}
                onClose={() => setSelectedLog(null)}
                variant="large"
                aria-label="Poll log details">
                <ModalHeader title={
                    selectedLog
                        ? `Poll Log — ${selectedLog.status === "success" ? "Success" : "Error"} — ${new Date(selectedLog.createdOn).toLocaleString()}`
                        : "Poll Log"
                } />
                <ModalBody>
                    {selectedLog && (
                        <div>
                            <Flex alignItems={{ default: "alignItemsCenter" }}
                                style={{ marginBottom: "16px", gap: "12px" }}>
                                <FlexItem>
                                    <Label color={selectedLog.status === "success" ? "green" : "red"}>
                                        {selectedLog.status}
                                    </Label>
                                </FlexItem>
                                <FlexItem>
                                    <span style={{ fontWeight: 600 }}>{selectedLog.message}</span>
                                </FlexItem>
                                {selectedLog.eventsIngested != null && selectedLog.eventsIngested > 0 && (
                                    <FlexItem>
                                        <Label isCompact color="blue">
                                            {selectedLog.eventsIngested} event(s)
                                        </Label>
                                    </FlexItem>
                                )}
                            </Flex>
                            {selectedLog.detail ? (
                                <CodeBlock>
                                    <CodeBlockCode>{selectedLog.detail}</CodeBlockCode>
                                </CodeBlock>
                            ) : (
                                <p className="axiom-text-subtle" style={{ fontStyle: "italic" }}>
                                    No detailed log available for this poll cycle.
                                </p>
                            )}
                        </div>
                    )}
                </ModalBody>
            </Modal>
        </div>
    );
}

function buildUrlFromConfig(source: EventSource): string {
    const config = source.configuration as Record<string, string> | undefined;
    if (!config) return "";
    if (source.sourceType === "github") {
        const owner = config.owner || "";
        const name = config.name || "";
        const instance = config.instance || "github.com";
        return owner && name ? `https://${instance}/${owner}/${name}` : "";
    }
    if (source.sourceType === "jira") {
        return config.url || "";
    }
    return "";
}

function buildConfigFromUrl(sourceType: string, url: string): Record<string, string> | undefined {
    if (!url) return undefined;
    try {
        const parsed = new URL(url);
        if (sourceType === "github") {
            const parts = parsed.pathname.split("/").filter(Boolean);
            return {
                owner: parts[0] || "",
                name: parts[1] || "",
                instance: parsed.hostname,
            };
        }
        if (sourceType === "jira") {
            const parts = parsed.pathname.split("/").filter(Boolean);
            let project = "";
            for (let i = 0; i < parts.length; i++) {
                if (parts[i] === "projects" && i + 1 < parts.length) {
                    project = parts[i + 1];
                    break;
                }
            }
            return {
                url: url,
                baseUrl: parsed.origin,
                project: project,
            };
        }
    } catch {
        // invalid URL
    }
    return undefined;
}
