import { useState, useEffect, useCallback, useRef } from "react";
import { useParams, Link } from "react-router-dom";
import {
    Breadcrumb,
    BreadcrumbItem,
    Button,
    Card,
    CardBody,
    CardTitle,
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
    DescriptionList,
    DescriptionListDescription,
    DescriptionListGroup,
    DescriptionListTerm,
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
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import CheckCircleIcon from "@patternfly/react-icons/dist/esm/icons/check-circle-icon";
import TimesCircleIcon from "@patternfly/react-icons/dist/esm/icons/times-circle-icon";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { useEffectiveTheme } from "../hooks/useTheme";
import { EditLabelsModal } from "../components/EditLabelsModal";
import { LabelDisplay } from "../components/LabelDisplay";
import {
    type EventSource,
    type EventSourceLog,
    type EventSourceFilters,
    type EventSourceFilterRule,
    type FilterDryRunRequest,
    type FilterDryRunResponse,
    type FilterDryRunResult,
    type Secret,
    fetchEventSource,
    updateEventSource,
    fetchEventSourceLogs,
    fetchSecrets,
    dryRunFilters,
} from "../config/api";

export function EventSourceDetailPage() {
    const { eventSourceId } = useParams<{ eventSourceId: string }>();
    const id = Number(eventSourceId);

    const [source, setSource] = useState<EventSource | null>(null);
    const [form, setForm] = useState<Partial<EventSource>>({});
    const [filters, setFilters] = useState<EventSourceFilters>({ include: [], exclude: [] });
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
    const [isLabelsOpen, setIsLabelsOpen] = useState(false);

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
                    labels: src.labels || [],
                });
                setFilters(src.filters ?? { include: [], exclude: [] });
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

    const updateFilters = (updated: EventSourceFilters) => {
        setFilters(updated);
        setDirty(true);
    };

    const handleSave = () => {
        setSaving(true);
        const config = buildConfigFromUrl(form.sourceType || "", sourceUrl);
        const data = { ...form, configuration: config, filters } as EventSource;
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
                            secrets={secrets}
                            labels={form.labels || []}
                            onEditLabels={() => setIsLabelsOpen(true)} />
                    </TabContent>
                </Tab>
                <Tab eventKey={1} title={<TabTitleText>Filters</TabTitleText>}>
                    <TabContent id="filters-tab" eventKey={1} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        {source && (
                            <FiltersTab
                                source={source}
                                filters={filters}
                                onFiltersChange={updateFilters}
                                isActive={activeTab === 1}
                            />
                        )}
                    </TabContent>
                </Tab>
                <Tab eventKey={2} title={<TabTitleText>
                    Poll Logs{logs.length > 0 ? ` (${logs.length})` : ""}
                </TabTitleText>}>
                    <TabContent id="logs-tab" eventKey={2} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <LogsTab logs={logs} totalCount={logsTotalCount}
                            page={logsPage} perPage={logsPerPage}
                            onPageChange={(p) => { setLogsPage(p); loadLogs(p); }}
                            onPerPageChange={(pp) => { setLogsPerPage(pp); setLogsPage(1); loadLogs(1, pp); }}
                            onRefresh={() => loadLogs()} />
                    </TabContent>
                </Tab>
            </Tabs>

            <EditLabelsModal
                isOpen={isLabelsOpen}
                labels={form.labels || []}
                onSave={async (labels) => {
                    updateForm({ labels });
                }}
                onClose={() => setIsLabelsOpen(false)}
            />
        </PageSection>
    );
}

function InfoTab({ form, updateForm, sourceUrl, setSourceUrl, secrets, labels, onEditLabels }: {
    form: Partial<EventSource>;
    updateForm: (updates: Partial<EventSource>) => void;
    sourceUrl: string;
    setSourceUrl: (v: string) => void;
    secrets: Secret[];
    labels: string[];
    onEditLabels: () => void;
}) {
    return (
        <Form style={{ maxWidth: "600px" }}>
            <FormGroup label="Name" isRequired fieldId="name">
                <TextInput id="name" isRequired value={form.name || ""}
                    onChange={(_e, v) => updateForm({ name: v })} />
            </FormGroup>
            <FormGroup label="Labels" fieldId="labels">
                <LabelDisplay labels={labels} onEdit={onEditLabels} />
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

function FiltersTab({ source, filters, onFiltersChange, isActive }: {
    source: EventSource;
    filters: EventSourceFilters;
    onFiltersChange: (filters: EventSourceFilters) => void;
    isActive: boolean;
}) {
    const [dryRunResults, setDryRunResults] = useState<FilterDryRunResponse | null>(null);
    const [dryRunLoading, setDryRunLoading] = useState(false);
    const [selectedResult, setSelectedResult] = useState<FilterDryRunResult | null>(null);
    const prevActiveRef = useRef(false);

    const [addingTo, setAddingTo] = useState<"include" | "exclude" | null>(null);
    const [newRuleType, setNewRuleType] = useState<"event-type" | "payload">("event-type");
    const [newRulePointer, setNewRulePointer] = useState("");
    const [newRulePattern, setNewRulePattern] = useState("");

    const handleAddRule = (list: "include" | "exclude") => {
        const rule: EventSourceFilterRule = {
            type: newRuleType,
            pattern: newRulePattern,
            ...(newRuleType === "payload" ? { pointer: newRulePointer } : {}),
        };
        const updated = { ...filters };
        updated[list] = [...updated[list], rule];
        onFiltersChange(updated);
        setAddingTo(null);
        setNewRuleType("event-type");
        setNewRulePointer("");
        setNewRulePattern("");
    };

    const handleDeleteRule = (list: "include" | "exclude", index: number) => {
        const updated = { ...filters };
        updated[list] = updated[list].filter((_, i) => i !== index);
        onFiltersChange(updated);
    };

    const handleDryRun = useCallback(async () => {
        setDryRunLoading(true);
        try {
            const request: FilterDryRunRequest = {
                sourceType: source.sourceType,
                configuration: source.configuration ?? {},
                secretName: source.secretName,
                filters,
            };
            const response = await dryRunFilters(request);
            setDryRunResults(response);
        } catch (e) {
            console.error("Dry run failed:", e);
        } finally {
            setDryRunLoading(false);
        }
    }, [source, filters]);

    useEffect(() => {
        if (isActive && !prevActiveRef.current) {
            handleDryRun();
        }
        prevActiveRef.current = isActive;
    }, [isActive, handleDryRun]);

    useEffect(() => {
        if (isActive) {
            handleDryRun();
        }
    }, [filters]); // eslint-disable-line react-hooks/exhaustive-deps

    const renderRuleTable = (list: "include" | "exclude", rules: EventSourceFilterRule[]) => (
        <Card style={{ marginBottom: "24px" }}>
            <CardTitle>
                {list === "include" ? "Include Rules" : "Exclude Rules"}
            </CardTitle>
            <CardBody>
                <Table aria-label={`${list} rules`} variant="compact">
                    <Thead>
                        <Tr>
                            <Th>Type</Th>
                            <Th>Pointer</Th>
                            <Th>Pattern</Th>
                            <Th>Actions</Th>
                        </Tr>
                    </Thead>
                    <Tbody>
                        {rules.map((rule, index) => (
                            <Tr key={index}>
                                <Td>{rule.type}</Td>
                                <Td>{rule.pointer ?? "—"}</Td>
                                <Td><code>{rule.pattern}</code></Td>
                                <Td>
                                    <Button variant="plain" aria-label="Delete rule"
                                        onClick={() => handleDeleteRule(list, index)}>
                                        <TrashIcon />
                                    </Button>
                                </Td>
                            </Tr>
                        ))}
                        {addingTo === list && (
                            <Tr>
                                <Td>
                                    <FormSelect value={newRuleType}
                                        onChange={(_e, v) => setNewRuleType(v as "event-type" | "payload")}>
                                        <FormSelectOption value="event-type" label="event-type" />
                                        <FormSelectOption value="payload" label="payload" />
                                    </FormSelect>
                                </Td>
                                <Td>
                                    {newRuleType === "payload" ? (
                                        <TextInput value={newRulePointer}
                                            onChange={(_e, v) => setNewRulePointer(v)}
                                            placeholder="/path/to/field" />
                                    ) : (
                                        <span>—</span>
                                    )}
                                </Td>
                                <Td>
                                    <TextInput value={newRulePattern}
                                        onChange={(_e, v) => setNewRulePattern(v)}
                                        placeholder="*[bot]" />
                                </Td>
                                <Td>
                                    <Button variant="primary" size="sm" style={{ marginRight: "8px" }}
                                        isDisabled={!newRulePattern}
                                        onClick={() => handleAddRule(list)}>
                                        Add
                                    </Button>
                                    <Button variant="link" size="sm"
                                        onClick={() => {
                                            setAddingTo(null);
                                            setNewRuleType("event-type");
                                            setNewRulePointer("");
                                            setNewRulePattern("");
                                        }}>
                                        Cancel
                                    </Button>
                                </Td>
                            </Tr>
                        )}
                    </Tbody>
                </Table>
                {addingTo !== list && (
                    <Button variant="link" onClick={() => setAddingTo(list)}
                        style={{ marginTop: "8px" }}>
                        Add {list} rule
                    </Button>
                )}
            </CardBody>
        </Card>
    );

    return (
        <Flex direction={{ default: "column", lg: "row" }}
            alignItems={{ default: "alignItemsStretch" }}
            style={{ gap: "24px" }}>
            <FlexItem flex={{ default: "flex_1" }}>
                <p className="axiom-text-subtle" style={{ marginBottom: "16px" }}>
                    Configure filter rules to control which events are processed. Include rules
                    allow matching events; exclude rules block them.
                </p>

                {renderRuleTable("include", filters.include)}
                {renderRuleTable("exclude", filters.exclude)}
            </FlexItem>

            <FlexItem flex={{ default: "flex_1" }}>
                <Card>
                    <CardTitle>
                        <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                            alignItems={{ default: "alignItemsCenter" }}>
                            <FlexItem>Matching Events Preview</FlexItem>
                            <FlexItem>
                                <Button variant="secondary" icon={<SyncAltIcon />}
                                    onClick={handleDryRun}
                                    isLoading={dryRunLoading}
                                    isDisabled={dryRunLoading}>
                                    Refresh
                                </Button>
                            </FlexItem>
                        </Flex>
                    </CardTitle>
                    <CardBody>
                        {!dryRunResults ? (
                            <EmptyState>
                                <EmptyStateBody>
                                    Click "Refresh" to preview how the current rules would apply
                                    to recent events.
                                </EmptyStateBody>
                            </EmptyState>
                        ) : (
                            <>
                                <p style={{ marginBottom: "12px" }}>
                                    <strong>{dryRunResults.totalAllowed} allowed</strong>,{" "}
                                    <strong>{dryRunResults.totalBlocked} blocked</strong> out of{" "}
                                    <strong>{dryRunResults.totalEvaluated}</strong> events
                                </p>
                                <div style={{ maxHeight: "60vh", overflow: "auto" }}>
                                    <Table aria-label="Filter test results" variant="compact">
                                        <Thead>
                                            <Tr>
                                                <Th>Result</Th>
                                                <Th>Event Type</Th>
                                                <Th>Reference</Th>
                                                <Th>Summary</Th>
                                                <Th>Matched Rule</Th>
                                            </Tr>
                                        </Thead>
                                        <Tbody>
                                            {dryRunResults.results.map((result, index) => (
                                                <Tr key={index} isClickable
                                                    onRowClick={() => setSelectedResult(result)}>
                                                    <Td>
                                                        <Label isCompact
                                                            color={result.allowed ? "green" : "red"}
                                                            icon={result.allowed
                                                                ? <CheckCircleIcon />
                                                                : <TimesCircleIcon />}>
                                                            {result.allowed ? "Allowed" : "Blocked"}
                                                        </Label>
                                                    </Td>
                                                    <Td>{result.eventType}</Td>
                                                    <Td>{result.issueRef}</Td>
                                                    <Td>{result.summary}</Td>
                                                    <Td>{result.matchedRule ?? "—"}</Td>
                                                </Tr>
                                            ))}
                                        </Tbody>
                                    </Table>
                                </div>
                            </>
                        )}
                    </CardBody>
                </Card>
            </FlexItem>

            <DryRunEventDetailModal
                result={selectedResult}
                onClose={() => setSelectedResult(null)}
            />
        </Flex>
    );
}

function DryRunEventDetailModal({ result, onClose }: {
    result: FilterDryRunResult | null;
    onClose: () => void;
}) {
    const effectiveTheme = useEffectiveTheme();
    const formatPayload = (payload?: string): string => {
        if (!payload) return "";
        try {
            return JSON.stringify(JSON.parse(payload), null, 2);
        } catch {
            return payload;
        }
    };

    return (
        <Modal isOpen={result !== null} onClose={onClose} variant="large"
            aria-label="Event details">
            <ModalHeader
                title="Event Details"
                description={result
                    ? `${result.eventType} — ${result.issueRef}`
                    : undefined}
            />
            <ModalBody>
                {result && (
                    <>
                        <Card style={{ marginBottom: "8px" }}>
                            <CardBody>
                                <DescriptionList isHorizontal isCompact>
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Event Type</DescriptionListTerm>
                                        <DescriptionListDescription>
                                            {result.eventType}
                                        </DescriptionListDescription>
                                    </DescriptionListGroup>
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Reference</DescriptionListTerm>
                                        <DescriptionListDescription>
                                            {result.issueRef}
                                        </DescriptionListDescription>
                                    </DescriptionListGroup>
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Summary</DescriptionListTerm>
                                        <DescriptionListDescription>
                                            {result.summary}
                                        </DescriptionListDescription>
                                    </DescriptionListGroup>
                                    <DescriptionListGroup>
                                        <DescriptionListTerm>Result</DescriptionListTerm>
                                        <DescriptionListDescription>
                                            <Label isCompact
                                                color={result.allowed ? "green" : "red"}
                                                icon={result.allowed
                                                    ? <CheckCircleIcon />
                                                    : <TimesCircleIcon />}>
                                                {result.allowed ? "Allowed" : "Blocked"}
                                            </Label>
                                        </DescriptionListDescription>
                                    </DescriptionListGroup>
                                    {result.matchedRule && (
                                        <DescriptionListGroup>
                                            <DescriptionListTerm>Matched Rule</DescriptionListTerm>
                                            <DescriptionListDescription>
                                                {result.matchedRule}
                                            </DescriptionListDescription>
                                        </DescriptionListGroup>
                                    )}
                                </DescriptionList>
                            </CardBody>
                        </Card>

                        <Title headingLevel="h4" size="md" style={{ marginBottom: "8px" }}>
                            Payload
                        </Title>
                        <CodeEditor
                            code={formatPayload(result.payload)}
                            language={Language.json}
                            isDarkTheme={effectiveTheme === "dark"}
                            height="400px"
                            isReadOnly
                            isLineNumbersVisible
                        />
                    </>
                )}
            </ModalBody>
        </Modal>
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
