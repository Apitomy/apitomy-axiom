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
    PageSection,
    TextArea,
    TextInput,
    Title,
} from "@patternfly/react-core";
import { AddToolInput } from "../components/AddToolInput";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import {
    type Toolset,
    type NewToolset,
    fetchToolset,
    updateToolset,
} from "../config/api";

export function ToolsetDetailPage() {
    const { toolsetId } = useParams<{ toolsetId: string }>();
    const id = Number(toolsetId);

    const [toolset, setToolset] = useState<Toolset | null>(null);
    const [form, setForm] = useState<NewToolset>({ name: "", tools: [] });
    const [tools, setTools] = useState<string[]>([]);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);

    const loadData = useCallback(() => {
        if (!id) return;
        setLoading(true);
        fetchToolset(id)
            .then((ts) => {
                setToolset(ts);
                setForm({
                    name: ts.name,
                    description: ts.description,
                    tools: ts.tools,
                });
                setTools(ts.tools || []);
                setDirty(false);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [id]);

    useEffect(() => { loadData(); }, [loadData]);

    const updateForm = (updates: Partial<NewToolset>) => {
        setForm((prev) => ({ ...prev, ...updates }));
        setDirty(true);
    };

    const addTool = (tool: string) => {
        if (tool && !tools.includes(tool)) {
            setTools([...tools, tool]);
            setDirty(true);
        }
    };

    const removeTool = (tool: string) => {
        setTools(tools.filter((t) => t !== tool));
        setDirty(true);
    };

    const replaceTools = (newTools: string[]) => {
        setTools(newTools);
        setDirty(true);
    };

    const handleSave = () => {
        setSaving(true);
        const data = { ...form, tools };
        updateToolset(id, data)
            .then((updated) => { setToolset(updated); setDirty(false); })
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

    if (!toolset) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Toolset not found.</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: "16px" }}>
                <BreadcrumbItem><Link to="/toolsets">Toolsets</Link></BreadcrumbItem>
                <BreadcrumbItem isActive>{toolset.name}</BreadcrumbItem>
            </Breadcrumb>

            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "24px" }}>
                <FlexItem>
                    <Title headingLevel="h1" size="lg">{toolset.name}</Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="primary" icon={<SaveIcon />} onClick={handleSave}
                        isDisabled={!dirty || !form.name || saving} isLoading={saving}>
                        {saving ? "Saving..." : "Save Changes"}
                    </Button>
                </FlexItem>
            </Flex>

            <Form style={{ maxWidth: "600px", marginBottom: "32px" }}>
                <FormGroup label="Name" isRequired fieldId="name">
                    <TextInput id="name" isRequired value={form.name}
                        onChange={(_e, v) => updateForm({ name: v })} />
                </FormGroup>
                <FormGroup label="Description" fieldId="description">
                    <TextArea id="description" value={form.description || ""}
                        onChange={(_e, v) => updateForm({ description: v })} rows={3} />
                </FormGroup>
            </Form>

            <div style={{ maxWidth: "700px" }}>
                <Title headingLevel="h2" size="md" style={{ marginBottom: "8px" }}>
                    Tools ({tools.length})
                </Title>
                <p style={{ color: "#6a6e73", marginBottom: "16px" }}>
                    Define the tools in this toolset. You can add individual tool
                    patterns like <code>Bash(git log *)</code> or reference other
                    toolsets using the <code>@ToolsetName</code> syntax.
                </p>

                <div style={{ marginBottom: "16px" }}>
                    <AddToolInput onAdd={addTool} onReplace={replaceTools} existingTools={tools} />
                </div>

                {tools.length === 0 ? (
                    <EmptyState variant="xs">
                        <EmptyStateBody>
                            No tools in this toolset yet.
                        </EmptyStateBody>
                    </EmptyState>
                ) : (
                    <div style={{ display: "flex", flexDirection: "column", gap: "4px" }}>
                        {tools.map((tool) => (
                            <Flex key={tool}
                                alignItems={{ default: "alignItemsCenter" }}
                                style={{
                                    padding: "8px 12px",
                                    backgroundColor: tool.startsWith("@")
                                        ? "var(--pf-t--global--background--color--primary--default)"
                                        : "var(--pf-t--global--background--color--secondary--default)",
                                    borderRadius: "4px",
                                    border: tool.startsWith("@")
                                        ? "1px solid var(--pf-t--global--border--color--default)"
                                        : "none",
                                }}>
                                <FlexItem grow={{ default: "grow" }}>
                                    <code style={{
                                        fontSize: "13px",
                                        color: tool.startsWith("@")
                                            ? "var(--pf-t--global--color--brand--default)"
                                            : "inherit",
                                    }}>
                                        {tool}
                                    </code>
                                </FlexItem>
                                <FlexItem>
                                    <Button variant="plain" size="sm"
                                        onClick={() => removeTool(tool)}
                                        aria-label={`Remove ${tool}`}>
                                        <TimesIcon />
                                    </Button>
                                </FlexItem>
                            </Flex>
                        ))}
                    </div>
                )}
            </div>
        </PageSection>
    );
}
