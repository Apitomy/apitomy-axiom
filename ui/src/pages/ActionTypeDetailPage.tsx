import { useState, useEffect, useCallback } from "react";
import { useParams, Link } from "react-router-dom";
import {
    Alert,
    AlertActionCloseButton,
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
import { ActionTypeEditor, type ValidationMessage } from "../components/ActionTypeEditor";
import {
    type ActionType,
    type NewActionType,
    fetchActionType,
    updateActionType,
} from "../config/api";

/**
 * Detail page for a single action type at /action-types/:id: loads, edits (via
 * {@link ActionTypeEditor}) and saves the action type.
 */
export function ActionTypeDetailPage() {
    const { actionTypeId } = useParams<{ actionTypeId: string }>();
    const id = Number(actionTypeId);

    const [actionType, setActionType] = useState<ActionType | null>(null);
    const [form, setForm] = useState<NewActionType>({
        name: "", executionMode: "agent", inputs: [], outputs: [],
    });
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [saveError, setSaveError] = useState<string | null>(null);
    const [dirty, setDirty] = useState(false);
    const [validationMessages, setValidationMessages] = useState<ValidationMessage[]>([]);

    const loadData = useCallback(() => {
        if (!id) return;
        setLoading(true);
        fetchActionType(id)
            .then((at) => {
                setActionType(at);
                setForm({
                    name: at.name,
                    description: at.description,
                    executionMode: at.executionMode,
                    inputs: at.inputs || [],
                    outputs: at.outputs || [],
                    allowedTools: at.allowedTools || [],
                    environment: at.environment || {},
                    promptTemplate: at.promptTemplate,
                    scriptTemplate: at.scriptTemplate,
                    codeTemplate: at.codeTemplate,
                    model: at.model,
                    engine: at.engine,
                    maxSteps: at.maxSteps,
                    maxBudgetUsd: at.maxBudgetUsd,
                    timeoutSeconds: at.timeoutSeconds,
                    labels: at.labels || [],
                    mcpServers: at.mcpServers || [],
                });
                setDirty(false);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [id]);

    useEffect(() => { loadData(); }, [loadData]);

    const handleSave = () => {
        setSaving(true);
        setSaveError(null);
        const env = form.environment || {};
        const data: NewActionType = {
            ...form,
            allowedTools: form.allowedTools || [],
            environment: Object.keys(env).length > 0 ? env : undefined,
        };
        updateActionType(id, data)
            .then((updated) => {
                setActionType(updated);
                setDirty(false);
            })
            .catch((err) => {
                console.error(err);
                setSaveError(err instanceof Error ? err.message : "Failed to save action type.");
            })
            .finally(() => setSaving(false));
    };

    if (loading) {
        return (
            <PageSection>
                <EmptyState>
                    <EmptyStateBody>Loading action type...</EmptyStateBody>
                </EmptyState>
            </PageSection>
        );
    }

    if (!actionType) {
        return (
            <PageSection>
                <EmptyState>
                    <EmptyStateBody>Action type not found.</EmptyStateBody>
                </EmptyState>
            </PageSection>
        );
    }

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: "16px" }}>
                <BreadcrumbItem><Link to="/action-types">Action Types</Link></BreadcrumbItem>
                <BreadcrumbItem isActive>{actionType.name}</BreadcrumbItem>
            </Breadcrumb>

            <Flex
                justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}
            >
                <FlexItem>
                    <Title headingLevel="h1" size="lg">
                        {actionType.name}
                    </Title>
                </FlexItem>
                <FlexItem>
                    <Button
                        variant="primary"
                        icon={<SaveIcon />}
                        onClick={handleSave}
                        isDisabled={!dirty || !form.name || saving || validationMessages.some((m) => m.severity === "error")}
                        isLoading={saving}
                    >
                        {saving ? "Saving..." : "Save Changes"}
                    </Button>
                </FlexItem>
            </Flex>

            {saveError && (
                <Alert
                    variant="danger"
                    isInline
                    title="Could not save action type"
                    actionClose={<AlertActionCloseButton onClose={() => setSaveError(null)} />}
                    style={{ marginBottom: "16px" }}
                >
                    {saveError}
                </Alert>
            )}

            <ActionTypeEditor
                value={form}
                onChange={(v) => { setForm(v); setDirty(true); }}
                onValidation={setValidationMessages}
            />
        </PageSection>
    );
}
