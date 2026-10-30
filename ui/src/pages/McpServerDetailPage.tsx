import { useState, useEffect, useCallback } from "react";
import { useParams, Link } from "react-router-dom";
import {
    Alert,
    Breadcrumb,
    BreadcrumbItem,
    Button,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    FormHelperText,
    HelperText,
    HelperTextItem,
    PageSection,
    Radio,
    Switch,
    Tab,
    TabContent,
    TabTitleText,
    Tabs,
    TextArea,
    TextInput,
    Title,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import {
    type McpServer,
    type McpServerTestResult,
    type McpServerUsage,
    type McpTransport,
    type NewMcpServer,
    fetchMcpServer,
    fetchMcpServerUsage,
    mcpServerUsageRoute,
    updateMcpServer,
    testMcpServer,
} from "../config/api";
import { McpTestResultPanel } from "../components/McpTestResultPanel";
import { KeyValueSecretEditor } from "../components/KeyValueSecretEditor";
import { StringListEditor } from "../components/StringListEditor";

const USAGE_TYPE_LABELS: Record<string, string> = {
    actionType: "Action type",
    sessionTemplate: "Session template",
    toolset: "Toolset",
};

const USAGE_FIELD_LABELS: Record<string, string> = {
    mcpServers: "selects the server",
    allowedTools: "allows its tools",
    tools: "includes its tools",
};

/**
 * Builds the PUT payload: keeps only the fields that belong to the chosen transport.
 */
export function toPayload(form: NewMcpServer): NewMcpServer {
    const transport: McpTransport = form.transport ?? (form.serverUrl?.trim() ? "http" : "stdio");
    const base: NewMcpServer = {
        name: form.name,
        description: form.description,
        transport,
        timeout: form.timeout,
        enabled: form.enabled ?? true,
    };
    if (transport === "stdio") {
        return { ...base, serverCommand: form.serverCommand, serverArgs: form.serverArgs ?? [],
            serverEnv: form.serverEnv ?? {} };
    }
    return { ...base, serverUrl: form.serverUrl, headers: form.headers ?? {} };
}

export function McpServerDetailPage() {
    const { mcpServerId } = useParams<{ mcpServerId: string }>();
    const id = Number(mcpServerId);
    const [server, setServer] = useState<McpServer | null>(null);
    const [form, setForm] = useState<NewMcpServer>({ name: "" });
    const [usages, setUsages] = useState<McpServerUsage[]>([]);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [activeTab, setActiveTab] = useState(0);
    const [saveError, setSaveError] = useState<string | null>(null);
    const [testing, setTesting] = useState(false);
    const [testResult, setTestResult] = useState<McpServerTestResult | null>(null);

    const applyServer = (s: McpServer) => {
        setServer(s);
        setForm({
            name: s.name, description: s.description,
            serverCommand: s.serverCommand, serverUrl: s.serverUrl,
            serverArgs: s.serverArgs, serverEnv: s.serverEnv,
            transport: s.transport, headers: s.headers, timeout: s.timeout, enabled: s.enabled,
        });
        setDirty(false);
    };

    const loadData = useCallback(() => {
        if (!id) return;
        setLoading(true);
        fetchMcpServer(id)
            .then(applyServer)
            .catch(console.error)
            .finally(() => setLoading(false));
        fetchMcpServerUsage(id).then(setUsages).catch(() => setUsages([]));
    }, [id]);

    useEffect(() => { loadData(); }, [loadData]);

    const updateForm = (updates: Partial<NewMcpServer>) => {
        setForm((prev) => ({ ...prev, ...updates }));
        setDirty(true);
    };

    const handleSave = () => {
        setSaving(true);
        setSaveError(null);
        updateMcpServer(id, toPayload(form))
            .then(applyServer)
            .catch((e: Error) => setSaveError(e.message))
            .finally(() => setSaving(false));
    };

    const handleTest = () => {
        setTesting(true);
        setTestResult(null);
        testMcpServer(id)
            .then((result) => {
                setTestResult(result);
                if (result.success) {
                    setServer((prev) => prev ? { ...prev, tools: result.tools, toolsDiscoveredOn: new Date().toISOString() } : prev);
                }
            })
            .catch((e: Error) => setTestResult({ success: false, error: e.message, tools: [], durationMs: 0 }))
            .finally(() => setTesting(false));
    };

    if (loading) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    if (!server) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>MCP server not found.</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    const transport: McpTransport = form.transport ?? "stdio";
    const tools = server.tools ?? [];

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: "16px" }}>
                <BreadcrumbItem><Link to="/mcp-servers">MCP Servers</Link></BreadcrumbItem>
                <BreadcrumbItem isActive>{server.name}</BreadcrumbItem>
            </Breadcrumb>

            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}>
                <FlexItem>
                    <Title headingLevel="h1" size="lg">{server.name}</Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="secondary" onClick={handleTest} isLoading={testing}
                        isDisabled={testing || dirty} style={{ marginRight: "8px" }}
                        title={dirty ? "Save your changes before testing" : undefined}>
                        {testing ? "Testing..." : "Test connection"}
                    </Button>
                    <Button variant="primary" icon={<SaveIcon />} onClick={handleSave}
                        isDisabled={!dirty || !form.name || saving} isLoading={saving}>
                        {saving ? "Saving..." : "Save Changes"}
                    </Button>
                </FlexItem>
            </Flex>

            {!server.enabled && (
                <Alert variant="info" isInline style={{ marginBottom: "16px" }}
                    title="This server is disabled: it is not loaded in sessions or tasks." />
            )}

            {server.warnings?.map((w) => (
                <Alert key={w} variant="warning" isInline title={w} style={{ marginBottom: "16px" }} />
            ))}

            {testResult && (
                <div style={{ marginBottom: "16px" }}>
                    <McpTestResultPanel result={testResult} serverName={server.name} />
                </div>
            )}

            {saveError && (
                <Alert variant="danger" isInline title={saveError} style={{ marginBottom: "16px" }} />
            )}

            <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                <Tab eventKey={0} title={<TabTitleText>Info</TabTitleText>}>
                    <TabContent id="info-tab" eventKey={0} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <Form style={{ maxWidth: "600px" }}>
                            <FormGroup label="Name" isRequired fieldId="name">
                                <TextInput id="name" isRequired value={form.name}
                                    onChange={(_e, v) => updateForm({ name: v })} />
                                <FormHelperText><HelperText><HelperTextItem>
                                    Tools are named <code>{`mcp__${form.name || "<name>"}__<tool>`}</code>. Renaming
                                    updates every action type, session template and toolset that uses this server.
                                </HelperTextItem></HelperText></FormHelperText>
                            </FormGroup>
                            <FormGroup label="Description" fieldId="description">
                                <TextArea id="description" value={form.description || ""}
                                    onChange={(_e, v) => updateForm({ description: v })} rows={3} />
                            </FormGroup>
                            <FormGroup label="Status" fieldId="enabled">
                                <Switch id="enabled" label="Enabled"
                                    isChecked={form.enabled ?? true}
                                    onChange={(_e, checked) => updateForm({ enabled: checked })} />
                                <FormHelperText><HelperText><HelperTextItem>
                                    Disabled servers are kept but never loaded in sessions or tasks.
                                </HelperTextItem></HelperText></FormHelperText>
                            </FormGroup>
                        </Form>
                    </TabContent>
                </Tab>
                <Tab eventKey={1} title={<TabTitleText>Connection</TabTitleText>}>
                    <TabContent id="connection-tab" eventKey={1} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <Form style={{ maxWidth: "900px" }}>
                            <FormGroup label="Transport" role="radiogroup" fieldId="transport" isInline>
                                <Radio id="transport-stdio" name="transport" label="stdio (local command)"
                                    isChecked={transport === "stdio"} onChange={() => updateForm({ transport: "stdio" })} />
                                <Radio id="transport-http" name="transport" label="HTTP (streamable)"
                                    isChecked={transport === "http"} onChange={() => updateForm({ transport: "http" })} />
                                <Radio id="transport-sse" name="transport" label="SSE (legacy)"
                                    isChecked={transport === "sse"} onChange={() => updateForm({ transport: "sse" })} />
                            </FormGroup>
                            {transport === "stdio" ? (
                                <>
                                    <FormGroup label="Command" isRequired fieldId="serverCommand">
                                        <TextInput id="serverCommand" value={form.serverCommand || ""}
                                            onChange={(_e, v) => updateForm({ serverCommand: v })}
                                            placeholder="e.g. npx, uvx, docker, node" />
                                    </FormGroup>
                                    <FormGroup label="Arguments" fieldId="serverArgs">
                                        <StringListEditor label="Arguments" items={form.serverArgs ?? []}
                                            placeholder="e.g. -y"
                                            onChange={(serverArgs) => updateForm({ serverArgs })} />
                                    </FormGroup>
                                    <FormGroup label="Environment" fieldId="serverEnv">
                                        <FormHelperText><HelperText><HelperTextItem>
                                            The server's complete environment: it does not receive the task's or
                                            session's variables. Use a secret for tokens; secrets are resolved only
                                            when a session or task starts.
                                        </HelperTextItem></HelperText></FormHelperText>
                                        <KeyValueSecretEditor label="Environment" keyPlaceholder="Name"
                                            values={form.serverEnv ?? {}}
                                            onChange={(serverEnv) => updateForm({ serverEnv })} />
                                    </FormGroup>
                                </>
                            ) : (
                                <>
                                    <FormGroup label="URL" isRequired fieldId="serverUrl">
                                        <TextInput id="serverUrl" value={form.serverUrl || ""}
                                            onChange={(_e, v) => updateForm({ serverUrl: v })}
                                            placeholder={transport === "sse" ? "https://mcp.example.com/sse"
                                                : "https://mcp.example.com/mcp"} />
                                    </FormGroup>
                                    <FormGroup label="Headers" fieldId="headers">
                                        <FormHelperText><HelperText><HelperTextItem>
                                            Sent with every request, e.g. <code>Authorization</code> ={" "}
                                            <code>{"Bearer ${secret:API_TOKEN}"}</code>.
                                        </HelperTextItem></HelperText></FormHelperText>
                                        <KeyValueSecretEditor label="Headers" keyPlaceholder="Header"
                                            values={form.headers ?? {}}
                                            onChange={(headers) => updateForm({ headers })} />
                                    </FormGroup>
                                </>
                            )}
                            <FormGroup label="Timeout (ms)" fieldId="timeout">
                                <TextInput id="timeout" type="number" min={1} value={form.timeout ?? ""}
                                    style={{ maxWidth: "200px" }}
                                    onChange={(_e, v) => updateForm({ timeout: v ? Number(v) : undefined })}
                                    placeholder="Engine default" />
                                <FormHelperText><HelperText><HelperTextItem>
                                    Used by OpenCode and by connection tests. Claude Code uses its own default.
                                </HelperTextItem></HelperText></FormHelperText>
                            </FormGroup>
                        </Form>
                    </TabContent>
                </Tab>
                <Tab eventKey={2} title={<TabTitleText>Tools ({tools.length})</TabTitleText>}>
                    <TabContent id="tools-tab" eventKey={2} activeKey={activeTab} style={{ marginTop: "24px" }}>
                        {tools.length === 0 ? (
                            <EmptyState><EmptyStateBody>
                                No tools discovered yet. Use <strong>Test connection</strong> to list them.
                            </EmptyStateBody></EmptyState>
                        ) : (
                            <>
                                {server.toolsDiscoveredOn && (
                                    <p className="axiom-text-subtle" style={{ marginBottom: "8px" }}>
                                        Discovered {new Date(server.toolsDiscoveredOn).toLocaleString()}.
                                    </p>
                                )}
                                <Table aria-label="Discovered tools" variant="compact">
                                    <Thead><Tr><Th>Allowed tool name</Th><Th>Description</Th></Tr></Thead>
                                    <Tbody>
                                        {tools.map((t) => (
                                            <Tr key={t.name}>
                                                <Td><code>{`mcp__${server.name}__${t.name}`}</code></Td>
                                                <Td>{t.description || "—"}</Td>
                                            </Tr>
                                        ))}
                                    </Tbody>
                                </Table>
                            </>
                        )}
                    </TabContent>
                </Tab>
                <Tab eventKey={3} title={<TabTitleText>Used by ({usages.length})</TabTitleText>}>
                    <TabContent id="usage-tab" eventKey={3} activeKey={activeTab} style={{ marginTop: "24px" }}>
                        {usages.length === 0 ? (
                            <EmptyState><EmptyStateBody>
                                Not used by any action type, session template or toolset.
                            </EmptyStateBody></EmptyState>
                        ) : (
                            <Table aria-label="Used by" variant="compact">
                                <Thead><Tr><Th>Type</Th><Th>Name</Th><Th>How</Th></Tr></Thead>
                                <Tbody>
                                    {usages.map((u) => (
                                        <Tr key={`${u.type}-${u.name}-${u.field}`}>
                                            <Td>{USAGE_TYPE_LABELS[u.type]}</Td>
                                            <Td><Link to={mcpServerUsageRoute(u)}>{u.name}</Link></Td>
                                            <Td>{USAGE_FIELD_LABELS[u.field]}</Td>
                                        </Tr>
                                    ))}
                                </Tbody>
                            </Table>
                        )}
                    </TabContent>
                </Tab>
            </Tabs>
        </PageSection>
    );
}
