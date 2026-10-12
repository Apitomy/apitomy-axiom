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
    PageSection,
    Title,
} from "@patternfly/react-core";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import { ToolEditor, type ValidationMessage } from "../components/ToolEditor";
import {
    type ToolDefinition,
    type NewToolDefinition,
    fetchTool,
    updateTool,
} from "../config/api";

/**
 * Detail page for a single tool at /tools/:id: loads, edits (via {@link ToolEditor}) and saves the tool.
 */
export function ToolDetailPage() {
    const { toolId } = useParams<{ toolId: string }>();
    const id = Number(toolId);

    const [tool, setTool] = useState<ToolDefinition | null>(null);
    const [form, setForm] = useState<NewToolDefinition>({ name: "", parameters: [], labels: [] });
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [validationMessages, setValidationMessages] = useState<ValidationMessage[]>([]);

    // Warn on browser close/refresh with unsaved changes
    useEffect(() => {
        const handler = (e: BeforeUnloadEvent) => {
            if (dirty) {
                e.preventDefault();
            }
        };
        window.addEventListener("beforeunload", handler);
        return () => window.removeEventListener("beforeunload", handler);
    }, [dirty]);

    const loadData = useCallback(() => {
        if (!id) return;
        setLoading(true);
        fetchTool(id)
            .then((t) => {
                setTool(t);
                setForm({
                    name: t.name, description: t.description,
                    scriptTemplate: t.scriptTemplate,
                    parameters: t.parameters || [],
                    labels: t.labels || [],
                });
                setDirty(false);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [id]);

    useEffect(() => { loadData(); }, [loadData]);

    const handleSave = () => {
        setSaving(true);
        updateTool(id, form)
            .then((updated) => {
                setTool(updated);
                setDirty(false);
            })
            .catch(console.error)
            .finally(() => setSaving(false));
    };

    if (loading) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Loading tool...</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    if (!tool) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Tool not found.</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: "16px" }}>
                <BreadcrumbItem><Link to="/tools">Tools</Link></BreadcrumbItem>
                <BreadcrumbItem isActive>{tool.name}</BreadcrumbItem>
            </Breadcrumb>

            <Flex
                justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}
            >
                <FlexItem>
                    <Title headingLevel="h1" size="lg">{tool.name}</Title>
                </FlexItem>
                <FlexItem>
                    <Button
                        variant="primary" icon={<SaveIcon />}
                        onClick={handleSave}
                        isDisabled={!dirty || !form.name || saving || validationMessages.some((m) => m.severity === "error")}
                        isLoading={saving}
                    >
                        {saving ? "Saving..." : "Save Changes"}
                    </Button>
                </FlexItem>
            </Flex>

            <ToolEditor
                value={form}
                onChange={(v) => { setForm(v); setDirty(true); }}
                onValidation={setValidationMessages}
                toolId={id}
            />
        </PageSection>
    );
}
