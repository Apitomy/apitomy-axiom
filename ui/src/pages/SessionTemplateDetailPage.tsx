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
    TextArea,
    TextInput,
} from "@patternfly/react-core";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { ToolListEditor } from "../components/ToolListEditor";
import {
    fetchAssistantTemplate,
    updateAssistantTemplate,
    fetchMcpServers,
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

    const loadData = useCallback(() => {
        if (!templateId) return;
        setLoading(true);
        Promise.all([
            fetchAssistantTemplate(templateId),
            fetchMcpServers(),
        ])
            .then(([t, servers]) => {
                setTemplate(t);
                setForm({
                    name: t.name,
                    description: t.description,
                    systemPrompt: t.systemPrompt,
                    welcomeMessage: t.welcomeMessage,
                    workingDirectory: t.workingDirectory,
                    mcpServers: t.mcpServers,
                    allowedTools: t.allowedTools,
                });
                setMcpServers(servers);
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
        updateAssistantTemplate(templateId, form)
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

            <Form>
                <FormGroup label="Name" isRequired fieldId="name">
                    <TextInput id="name" value={form.name}
                        onChange={(_e, v) => updateForm({ name: v })}
                        isDisabled={isReadOnly} />
                </FormGroup>

                <FormGroup label="Description" isRequired fieldId="description">
                    <TextArea id="description" value={form.description}
                        onChange={(_e, v) => updateForm({ description: v })}
                        isDisabled={isReadOnly} rows={2} />
                </FormGroup>

                <FormGroup label="System Prompt" isRequired fieldId="systemPrompt">
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Markdown content written to CLAUDE.md in the session working
                                directory.
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                    <CodeEditor
                        code={form.systemPrompt}
                        onChange={(v) => updateForm({ systemPrompt: v })}
                        language={Language.markdown}
                        height="300px"
                        isReadOnly={isReadOnly}
                        options={{
                            quickSuggestions: false,
                            suggestOnTriggerCharacters: false,
                            wordBasedSuggestions: "off",
                            parameterHints: { enabled: false },
                        }}
                    />
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
                        isDisabled={isReadOnly} rows={4} />
                </FormGroup>

                <FormGroup label="Working Directory" fieldId="workingDirectory">
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Optional absolute path. If empty, Axiom creates a temporary
                                directory.
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                    <TextInput id="workingDirectory"
                        value={form.workingDirectory || ""}
                        onChange={(_e, v) =>
                            updateForm({ workingDirectory: v || undefined })
                        }
                        isDisabled={isReadOnly} />
                </FormGroup>

                <FormGroup label="MCP Servers" fieldId="mcpServers">
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Select MCP servers to include in the session. These are
                                resolved from your configured MCP servers.
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                    <div style={{
                        border: "1px solid #d2d2d2",
                        borderRadius: "3px",
                        padding: "8px",
                        maxHeight: "200px",
                        overflowY: "auto"
                    }}>
                        {mcpServers.length === 0 ? (
                            <div style={{ color: "#6a6e73", fontStyle: "italic" }}>
                                No MCP servers configured
                            </div>
                        ) : (
                            mcpServers.map((server) => (
                                <div key={server.name} style={{ marginBottom: "4px" }}>
                                    <label style={{ display: "flex", alignItems: "center", cursor: isReadOnly ? "not-allowed" : "pointer" }}>
                                        <input
                                            type="checkbox"
                                            checked={(form.mcpServers || []).includes(server.name)}
                                            onChange={() => toggleMcpServer(server.name)}
                                            disabled={isReadOnly}
                                            style={{ marginRight: "8px" }}
                                        />
                                        <span>{server.name}</span>
                                        {server.description && (
                                            <span style={{ marginLeft: "8px", color: "#6a6e73", fontSize: "0.9em" }}>
                                                — {server.description}
                                            </span>
                                        )}
                                    </label>
                                </div>
                            ))
                        )}
                    </div>
                </FormGroup>

                <FormGroup label="Allowed Tools" fieldId="allowedTools">
                    <ToolListEditor
                        tools={form.allowedTools || []}
                        onAdd={(tool) => {
                            updateForm({ allowedTools: [...(form.allowedTools || []), tool] });
                        }}
                        onRemove={(tool) => {
                            updateForm({
                                allowedTools: (form.allowedTools || []).filter((t) => t !== tool),
                            });
                        }}
                        onReplace={(tools) => {
                            updateForm({ allowedTools: tools });
                        }}
                        helpText={<>
                            Define which tools are automatically approved for sessions using this
                            template. Use patterns like <code>Bash(git log *)</code> to allow
                            specific shell commands. Reference a toolset using{" "}
                            <code>@ToolsetName</code> (e.g. <code>@Read-Only Tools</code>) to
                            include all tools from that collection.
                        </>}
                    />
                </FormGroup>
                {!isReadOnly && (
                    <FormGroup>
                        <Button variant="primary" onClick={handleSave}
                            isDisabled={!dirty || !form.name || saving}
                            isLoading={saving}>
                            Save
                        </Button>
                    </FormGroup>
                )}
            </Form>
        </PageSection>
    );
}
