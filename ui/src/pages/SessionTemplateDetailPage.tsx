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
import {
    fetchAssistantTemplate,
    updateAssistantTemplate,
    fetchMcpServers,
    fetchToolsets,
    type SessionTemplate,
    type NewSessionTemplate,
    type McpServer,
    type Toolset,
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
    const [toolsets, setToolsets] = useState<Toolset[]>([]);

    const loadData = useCallback(() => {
        if (!templateId) return;
        setLoading(true);
        Promise.all([
            fetchAssistantTemplate(templateId),
            fetchMcpServers(),
            fetchToolsets(),
        ])
            .then(([t, servers, ts]) => {
                setTemplate(t);
                setForm({
                    name: t.name,
                    description: t.description,
                    systemPrompt: t.systemPrompt,
                    welcomeMessage: t.welcomeMessage,
                    workingDirectory: t.workingDirectory,
                    mcpServers: t.mcpServers,
                    toolsets: t.toolsets,
                    allowedTools: t.allowedTools,
                });
                setMcpServers(servers);
                setToolsets(ts);
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

    const toggleToolset = (toolsetName: string) => {
        const current = form.toolsets || [];
        const updated = current.includes(toolsetName)
            ? current.filter((n) => n !== toolsetName)
            : [...current, toolsetName];
        updateForm({ toolsets: updated });
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

                <FormGroup label="Toolsets" fieldId="toolsets">
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Select toolsets whose tools are automatically approved.
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
                        {toolsets.length === 0 ? (
                            <div style={{ color: "#6a6e73", fontStyle: "italic" }}>
                                No toolsets configured
                            </div>
                        ) : (
                            toolsets.map((toolset) => (
                                <div key={toolset.name} style={{ marginBottom: "4px" }}>
                                    <label style={{ display: "flex", alignItems: "center", cursor: isReadOnly ? "not-allowed" : "pointer" }}>
                                        <input
                                            type="checkbox"
                                            checked={(form.toolsets || []).includes(toolset.name)}
                                            onChange={() => toggleToolset(toolset.name)}
                                            disabled={isReadOnly}
                                            style={{ marginRight: "8px" }}
                                        />
                                        <span>{toolset.name}</span>
                                        {toolset.description && (
                                            <span style={{ marginLeft: "8px", color: "#6a6e73", fontSize: "0.9em" }}>
                                                — {toolset.description}
                                            </span>
                                        )}
                                    </label>
                                </div>
                            ))
                        )}
                    </div>
                </FormGroup>

                <FormGroup label="Allowed Tools" fieldId="allowedTools">
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                Additional tool patterns (one per line, e.g. "Read(*)",
                                "Bash(ls *)").
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                    <TextArea id="allowedTools"
                        value={(form.allowedTools || []).join("\n")}
                        onChange={(_e, v) => updateForm({
                            allowedTools: v.split("\n")
                                .map((s) => s.trim())
                                .filter((s) => s.length > 0),
                        })}
                        isDisabled={isReadOnly} rows={4} />
                </FormGroup>
            </Form>
        </PageSection>
    );
}
