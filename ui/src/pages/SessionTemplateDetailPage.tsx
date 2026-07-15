import { useState, useEffect, useCallback } from "react";
import { useParams, Link } from "react-router-dom";
import {
    Alert,
    Breadcrumb,
    BreadcrumbItem,
    Button,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    FormHelperText,
    HelperText,
    HelperTextItem,
    Label,
    PageSection,
    Spinner,
    Tab,
    Tabs,
    TabTitleText,
    TextArea,
    TextInput,
    FormSelect,
    FormSelectOption,
    FormSelectOptionGroup,
} from "@patternfly/react-core";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { EnvironmentTab } from "../components/EnvironmentTab";
import { ToolListEditor } from "../components/ToolListEditor";
import {
    fetchAssistantTemplate,
    updateAssistantTemplate,
    fetchMcpServers,
    fetchModels,
    type SessionTemplate,
    type NewSessionTemplate,
    type McpServer,
} from "../config/api";

export function SessionTemplateDetailPage() {
    const { templateId } = useParams<{ templateId: string }>();
    const [template, setTemplate] = useState<SessionTemplate | null>(null);
    const [form, setForm] = useState<NewSessionTemplate>({
        name: "", description: "", systemPrompt: "",
    });
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [mcpServers, setMcpServers] = useState<McpServer[]>([]);
    const [availableModels, setAvailableModels] = useState<string[]>([]);
    const [envVars, setEnvVars] = useState<Record<string, string>>({});
    const [activeTab, setActiveTab] = useState(0);

    const loadData = useCallback(() => {
        if (!templateId) return;
        setLoading(true);
        Promise.all([
            fetchAssistantTemplate(templateId),
            fetchMcpServers(),
            fetchModels(),
        ])
            .then(([t, servers, models]) => {
                setTemplate(t);
                setForm({
                    name: t.name,
                    description: t.description,
                    systemPrompt: t.systemPrompt,
                    welcomeMessage: t.welcomeMessage,
                    workingDirectory: t.workingDirectory,
                    model: t.model,
                    initScript: t.initScript,
                    initScriptType: t.initScriptType,
                    mcpServers: t.mcpServers,
                    allowedTools: t.allowedTools,
                });
                setEnvVars(t.environment || {});
                setMcpServers(servers);
                setAvailableModels(models);
                setDirty(false);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [templateId]);

    useEffect(() => { loadData(); }, [loadData]);

    const updateForm = (updates: Partial<NewSessionTemplate>) => {
        setForm((prev) => ({ ...prev, ...updates }));
        setDirty(true);
    };

    const handleSave = () => {
        if (!templateId) return;
        setSaving(true);
        const envToSend = Object.keys(envVars).length > 0 ? envVars : undefined;
        updateAssistantTemplate(templateId, { ...form, environment: envToSend })
            .then((updated) => {
                setTemplate(updated);
                setDirty(false);
            })
            .catch(console.error)
            .finally(() => setSaving(false));
    };

    const toggleMcpServer = (serverName: string) => {
        const current = form.mcpServers || [];
        const updated = current.includes(serverName)
            ? current.filter((n) => n !== serverName)
            : [...current, serverName];
        updateForm({ mcpServers: updated });
    };

    if (loading) {
        return <PageSection><Spinner size="lg" /></PageSection>;
    }

    if (!template) {
        return (
            <PageSection>
                <Alert variant="danger" isInline title="Template not found" />
            </PageSection>
        );
    }

    const isReadOnly = template.builtIn;

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: 16 }}>
                <BreadcrumbItem>
                    <Link to="/session-templates">AI Assistant Templates</Link>
                </BreadcrumbItem>
                <BreadcrumbItem isActive>{template.name}</BreadcrumbItem>
            </Breadcrumb>

            <Flex style={{ marginBottom: 16 }}>
                <FlexItem grow={{ default: "grow" }}>
                    <span style={{ fontSize: "24px", fontWeight: 600 }}>
                        {template.name}
                    </span>
                    {isReadOnly && (
                        <Label color="blue" style={{ marginLeft: 12 }}>
                            Built-in (read-only)
                        </Label>
                    )}
                </FlexItem>
                {!isReadOnly && (
                    <FlexItem>
                        <Button variant="primary" onClick={handleSave}
                            isDisabled={!dirty || !form.name || saving}
                            isLoading={saving}>
                            Save
                        </Button>
                    </FlexItem>
                )}
            </Flex>

            <Tabs activeKey={activeTab}
                onSelect={(_e, key) => setActiveTab(key as number)}>

                <Tab eventKey={0} title={<TabTitleText>General</TabTitleText>}>
                    <Form style={{ marginTop: 24, maxWidth: 800 }}>
                        <FormGroup label="Name" isRequired fieldId="name">
                            <TextInput id="name" value={form.name}
                                onChange={(_e, v) => updateForm({ name: v })}
                                readOnlyVariant={isReadOnly ? "default" : undefined} />
                        </FormGroup>

                        <FormGroup label="Description" isRequired fieldId="description">
                            <TextArea id="description" value={form.description}
                                onChange={(_e, v) => updateForm({ description: v })}
                                readOnlyVariant={isReadOnly ? "default" : undefined} rows={3} />
                        </FormGroup>

                        <FormGroup label="Welcome Message" fieldId="welcomeMessage">
                            <FormHelperText>
                                <HelperText>
                                    <HelperTextItem>
                                        First message shown in the chat UI (supports markdown).
                                        Leave empty for no welcome message.
                                    </HelperTextItem>
                                </HelperText>
                            </FormHelperText>
                            <TextArea id="welcomeMessage"
                                value={form.welcomeMessage || ""}
                                onChange={(_e, v) =>
                                    updateForm({ welcomeMessage: v || undefined })
                                }
                                readOnlyVariant={isReadOnly ? "default" : undefined} rows={4} />
                        </FormGroup>

                        <FormGroup label="Working Directory" fieldId="workingDirectory">
                            <FormHelperText>
                                <HelperText>
                                    <HelperTextItem>
                                        Optional absolute path. If empty, Axiom creates a
                                        temporary directory.
                                    </HelperTextItem>
                                </HelperText>
                            </FormHelperText>
                            <TextInput id="workingDirectory"
                                value={form.workingDirectory || ""}
                                onChange={(_e, v) =>
                                    updateForm({ workingDirectory: v || undefined })
                                }
                                readOnlyVariant={isReadOnly ? "default" : undefined} />
                        </FormGroup>

                        <FormGroup label="Model" fieldId="model">
                            <FormHelperText>
                                <HelperText>
                                    <HelperTextItem>
                                        AI model to use for sessions. Select "Not specified"
                                        to use the default model.
                                    </HelperTextItem>
                                </HelperText>
                            </FormHelperText>
                            <FormSelect
                                id="model"
                                value={form.model || ""}
                                onChange={(_e, v) => updateForm({ model: v || undefined })}
                                isDisabled={isReadOnly}
                            >
                                <FormSelectOption value="" label="Not specified (use default)" />
                                {(() => {
                                    const hasProviders = availableModels.some((m) => m.includes("/"));
                                    if (hasProviders) {
                                        const groups: Record<string, string[]> = {};
                                        for (const m of availableModels) {
                                            if (m.includes("/")) {
                                                const [provider] = m.split("/", 2);
                                                const key = provider.charAt(0).toUpperCase() + provider.slice(1);
                                                if (!groups[key]) groups[key] = [];
                                                groups[key].push(m);
                                            } else {
                                                if (!groups["Other"]) groups["Other"] = [];
                                                groups["Other"].push(m);
                                            }
                                        }
                                        return Object.entries(groups).map(([provider, models]) => (
                                            <FormSelectOptionGroup key={provider} label={provider}>
                                                {models.map((m) => (
                                                    <FormSelectOption key={m} value={m}
                                                        label={m.split("/").pop() || m} />
                                                ))}
                                            </FormSelectOptionGroup>
                                        ));
                                    }
                                    return availableModels.map((m) => (
                                        <FormSelectOption key={m} value={m} label={m} />
                                    ));
                                })()}
                            </FormSelect>
                        </FormGroup>
                    </Form>
                </Tab>

                <Tab eventKey={1} title={<TabTitleText>System Prompt</TabTitleText>}>
                    <div style={{ marginTop: 24 }}>
                        <FormHelperText style={{ marginBottom: 12 }}>
                            <HelperText>
                                <HelperTextItem>
                                    Markdown content appended to the Claude Code system prompt
                                    for sessions using this template.
                                </HelperTextItem>
                            </HelperText>
                        </FormHelperText>
                        <CodeEditor
                            code={form.systemPrompt}
                            onChange={(v) => updateForm({ systemPrompt: v })}
                            language={Language.markdown}
                            height="500px"
                            isReadOnly={isReadOnly}
                            isLineNumbersVisible
                            options={{
                                quickSuggestions: false,
                                suggestOnTriggerCharacters: false,
                                wordBasedSuggestions: "off",
                                parameterHints: { enabled: false },
                                wordWrap: "on",
                            }}
                        />
                    </div>
                </Tab>

                <Tab eventKey={2} title={<TabTitleText>Tools &amp; Servers</TabTitleText>}>
                    <Form style={{ marginTop: 24, maxWidth: 800 }}>
                        <FormGroup label="Allowed Tools" fieldId="allowedTools">
                            <ToolListEditor
                                tools={form.allowedTools || []}
                                onAdd={(tool) => {
                                    updateForm({
                                        allowedTools: [...(form.allowedTools || []), tool],
                                    });
                                }}
                                onRemove={(tool) => {
                                    updateForm({
                                        allowedTools: (form.allowedTools || [])
                                            .filter((t) => t !== tool),
                                    });
                                }}
                                onReplace={(tools) => {
                                    updateForm({ allowedTools: tools });
                                }}
                                helpText={<>
                                    Define which tools are automatically approved for sessions
                                    using this template. Use patterns like{" "}
                                    <code>Bash(git log *)</code> to allow specific shell
                                    commands. Reference a toolset using{" "}
                                    <code>@ToolsetName</code> (e.g.{" "}
                                    <code>@Read-Only Tools</code>) to include all tools from
                                    that collection.
                                </>}
                            />
                        </FormGroup>

                        <FormGroup label="MCP Servers" fieldId="mcpServers">
                            <FormHelperText>
                                <HelperText>
                                    <HelperTextItem>
                                        Select MCP servers to include in the session. These
                                        are resolved from your configured MCP servers.
                                    </HelperTextItem>
                                </HelperText>
                            </FormHelperText>
                            <div style={{
                                border: "1px solid #d2d2d2",
                                borderRadius: "3px",
                                padding: "8px",
                                maxHeight: "200px",
                                overflowY: "auto",
                            }}>
                                {mcpServers.length === 0 ? (
                                    <div style={{
                                        color: "#6a6e73",
                                        fontStyle: "italic",
                                    }}>
                                        No MCP servers configured
                                    </div>
                                ) : (
                                    mcpServers.map((server) => (
                                        <div key={server.name}
                                            style={{ marginBottom: "4px" }}>
                                            <label style={{
                                                display: "flex",
                                                alignItems: "center",
                                                cursor: isReadOnly
                                                    ? "not-allowed" : "pointer",
                                            }}>
                                                <input
                                                    type="checkbox"
                                                    checked={(form.mcpServers || [])
                                                        .includes(server.name)}
                                                    onChange={() =>
                                                        toggleMcpServer(server.name)
                                                    }
                                                    disabled={isReadOnly}
                                                    style={{ marginRight: "8px" }}
                                                />
                                                <span>{server.name}</span>
                                                {server.description && (
                                                    <span style={{
                                                        marginLeft: "8px",
                                                        color: "#6a6e73",
                                                        fontSize: "0.9em",
                                                    }}>
                                                        — {server.description}
                                                    </span>
                                                )}
                                            </label>
                                        </div>
                                    ))
                                )}
                            </div>
                        </FormGroup>
                    </Form>
                </Tab>

                <Tab eventKey={3} title={
                    <TabTitleText>
                        Init Script{form.initScript ? " *" : ""}
                    </TabTitleText>
                }>
                    <div style={{ marginTop: 24 }}>
                        <FormHelperText style={{ marginBottom: 12 }}>
                            <HelperText>
                                <HelperTextItem>
                                    Optional script that runs in the working directory when a
                                    session is created. Use this to clone repositories, install
                                    dependencies, or set up project files. The script must
                                    complete within 60 seconds.
                                </HelperTextItem>
                            </HelperText>
                        </FormHelperText>
                        <Form style={{ maxWidth: 300, marginBottom: 12 }}>
                            <FormGroup label="Script Type" fieldId="initScriptType">
                                <FormSelect
                                    id="initScriptType"
                                    value={form.initScriptType || "bash"}
                                    onChange={(_e, v) => updateForm({ initScriptType: v })}
                                    isDisabled={isReadOnly}
                                >
                                    <FormSelectOption value="bash" label="Bash" />
                                    <FormSelectOption value="node" label="Node.js" />
                                </FormSelect>
                            </FormGroup>
                        </Form>
                        <CodeEditor
                            code={form.initScript || ""}
                            onChange={(v) => updateForm({
                                initScript: v || undefined,
                            })}
                            language={(form.initScriptType || "bash") === "bash"
                                ? Language.shell : Language.javascript}
                            height="400px"
                            isReadOnly={isReadOnly}
                            isLineNumbersVisible
                            options={{
                                quickSuggestions: false,
                                suggestOnTriggerCharacters: false,
                                wordBasedSuggestions: "off",
                                parameterHints: { enabled: false },
                            }}
                        />
                    </div>
                </Tab>

                <Tab eventKey={4} title={<TabTitleText>Environment</TabTitleText>}>
                    <div style={{ marginTop: 24 }}>
                        {isReadOnly ? (
                            <p style={{ color: "#6a6e73", fontStyle: "italic" }}>
                                Environment variables cannot be modified on built-in templates.
                            </p>
                        ) : (
                            <EnvironmentTab
                                envVars={envVars}
                                onChange={(updated) => {
                                    setEnvVars(updated);
                                    setDirty(true);
                                }}
                            />
                        )}
                    </div>
                </Tab>
            </Tabs>
        </PageSection>
    );
}
