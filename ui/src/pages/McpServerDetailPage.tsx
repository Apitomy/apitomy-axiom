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
    PageSection,
    Tab,
    TabContent,
    TabTitleText,
    Tabs,
    TextArea,
    TextInput,
    Title,
} from "@patternfly/react-core";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import {
    type McpServer,
    type NewMcpServer,
    type McpServerTestResult,
    fetchMcpServer,
    updateMcpServer,
    testMcpServer,
} from "../config/api";
import { McpTestResultPanel } from "../components/McpTestResultPanel";

export function McpServerDetailPage() {
    const { mcpServerId } = useParams<{ mcpServerId: string }>();
    const id = Number(mcpServerId);

    const [server, setServer] = useState<McpServer | null>(null);
    const [form, setForm] = useState<NewMcpServer>({ name: "" });
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [saveError, setSaveError] = useState<string | null>(null);
    const [testing, setTesting] = useState(false);
    const [testResult, setTestResult] = useState<McpServerTestResult | null>(null);

    const handleTest = () => {
        setTesting(true);
        setTestResult(null);
        testMcpServer(id)
            .then((result) => {
                setTestResult(result);
                if (result.success) {
                    setServer((prev) => prev ? { ...prev, tools: result.tools } : prev);
                }
            })
            .catch((e: Error) => setTestResult({ success: false, error: e.message, tools: [], durationMs: 0 }))
            .finally(() => setTesting(false));
    };
    const [activeTab, setActiveTab] = useState(0);

    const loadData = useCallback(() => {
        if (!id) return;
        setLoading(true);
        fetchMcpServer(id)
            .then((s) => {
                setServer(s);
                setForm({
                    name: s.name, description: s.description,
                    serverCommand: s.serverCommand, serverUrl: s.serverUrl,
                    serverArgs: s.serverArgs, serverEnv: s.serverEnv,
                    transport: s.transport, headers: s.headers, timeout: s.timeout, enabled: s.enabled,
                });
                setDirty(false);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [id]);

    useEffect(() => { loadData(); }, [loadData]);

    const updateForm = (updates: Partial<NewMcpServer>) => {
        setForm((prev) => ({ ...prev, ...updates }));
        setDirty(true);
    };

    const handleSave = () => {
        setSaving(true);
        setSaveError(null);
        // Keep the transport consistent with whichever connection field is populated.
        const transport = form.serverUrl?.trim()
            ? (form.transport === "sse" ? "sse" : "http")
            : "stdio";
        updateMcpServer(id, { ...form, transport })
            .then((updated) => { setServer(updated); setDirty(false); })
            .catch((e: Error) => setSaveError(e.message))
            .finally(() => setSaving(false));
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
                            </FormGroup>
                            <FormGroup label="Description" fieldId="description">
                                <TextArea id="description" value={form.description || ""}
                                    onChange={(_e, v) => updateForm({ description: v })} rows={3} />
                            </FormGroup>
                        </Form>
                    </TabContent>
                </Tab>
                <Tab eventKey={1} title={<TabTitleText>Connection</TabTitleText>}>
                    <TabContent id="connection-tab" eventKey={1} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <Form style={{ maxWidth: "600px" }}>
                            <p className="axiom-text-subtle" style={{ marginBottom: "16px" }}>
                                Configure the MCP server connection. Use either an HTTP URL
                                or a command (for stdio transport), but not both.
                            </p>
                            <FormGroup label="Server URL (HTTP transport)" fieldId="serverUrl">
                                <TextInput id="serverUrl" value={form.serverUrl || ""}
                                    onChange={(_e, v) => updateForm({ serverUrl: v })}
                                    placeholder="https://mcp.example.com/mcp" />
                            </FormGroup>
                            <FormGroup label="Server Command (stdio transport)" fieldId="serverCommand">
                                <TextInput id="serverCommand" value={form.serverCommand || ""}
                                    onChange={(_e, v) => updateForm({ serverCommand: v })}
                                    placeholder="e.g. npx, python3, node" />
                            </FormGroup>
                        </Form>
                    </TabContent>
                </Tab>
            </Tabs>
        </PageSection>
    );
}
