import { useState, useEffect, useCallback, useRef } from "react";
import { useEffectiveTheme } from "../hooks/useTheme";
import { useParams, Link } from "react-router-dom";
import {
    Breadcrumb,
    BreadcrumbItem,
    Button,
    Checkbox,
    EmptyState,
    EmptyStateBody,
    Flex,
    FlexItem,
    Form,
    FormGroup,
    FormSelect,
    FormSelectOption,
    FormSelectOptionGroup,
    PageSection,
    Tab,
    TabContent,
    TabTitleText,
    Tabs,
    TextArea,
    TextInput,
    Title, HelperText, HelperTextItem,
} from "@patternfly/react-core";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { registerPlaceholderCompletions, ACTION_TYPE_PLACEHOLDERS } from "../components/PlaceholderCompletionProvider";
import { EnvironmentTab } from "../components/EnvironmentTab";
import { ToolListEditor } from "../components/ToolListEditor";
import { ScriptAiModal } from "../components/ScriptAiModal";
import { ActionTypeAiModal } from "../components/ActionTypeAiModal";
import { ValidationProblemsPanel } from "../components/ValidationProblemsPanel";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import MagicIcon from "@patternfly/react-icons/dist/esm/icons/magic-icon";
import {
    type ActionType,
    type NewActionType,
    type ToolValidationMessage,
    fetchActionType,
    updateActionType,
    validateActionType,
    fetchModels,
    fetchEngines,
} from "../config/api";
import ExclamationTriangleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-triangle-icon";
import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";

export function ActionTypeDetailPage() {
    const { actionTypeId } = useParams<{ actionTypeId: string }>();
    const id = Number(actionTypeId);

    const [actionType, setActionType] = useState<ActionType | null>(null);
    const [form, setForm] = useState<NewActionType>({
        name: "", executionMode: "actor", userTriggerable: false, managerTriggerable: true, emitsEvent: true,
    });
    const [tools, setTools] = useState<string[]>([]);
    const [envVars, setEnvVars] = useState<Record<string, string>>({});
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [activeTab, setActiveTab] = useState(0);
    const [aiModalOpen, setAiModalOpen] = useState(false);
    const [availableModels, setAvailableModels] = useState<string[]>([]);
    const [availableEngines, setAvailableEngines] = useState<string[]>([]);
    const [validationMessages, setValidationMessages] = useState<ToolValidationMessage[]>([]);
    const validationTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    const buildValidationData = (
        formData: NewActionType,
        toolsList: string[],
        env: Record<string, string>
    ): NewActionType => ({
        ...formData,
        allowedTools: toolsList,
        environment: Object.keys(env).length > 0 ? env : undefined,
    });

    const runValidation = useCallback((data: NewActionType) => {
        if (validationTimerRef.current) {
            clearTimeout(validationTimerRef.current);
        }
        validationTimerRef.current = setTimeout(() => {
            validateActionType(data)
                .then((result) => setValidationMessages(result.messages))
                .catch(console.error);
        }, 500);
    }, []);

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
                    userTriggerable: at.userTriggerable,
                    managerTriggerable: at.managerTriggerable,
                    emitsEvent: at.emitsEvent,
                    inputSchema: at.inputSchema,
                    allowedTools: at.allowedTools,
                    promptTemplate: at.promptTemplate,
                    scriptTemplate: at.scriptTemplate,
                    model: at.model,
                    engine: at.engine,
                    maxSteps: at.maxSteps,
                    maxBudgetUsd: at.maxBudgetUsd,
                });
                setTools(at.allowedTools || []);
                setEnvVars(at.environment || {});
                setDirty(false);
                runValidation(buildValidationData(
                    at, at.allowedTools || [], at.environment || {}));
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [id, runValidation]);

    useEffect(() => { loadData(); }, [loadData]);
    useEffect(() => {
        fetchEngines().then(setAvailableEngines).catch(console.error);
    }, []);

    useEffect(() => {
        fetchModels(form.engine || undefined).then(setAvailableModels).catch(console.error);
    }, [form.engine]);

    const updateForm = (updates: Partial<NewActionType>) => {
        if (updates.engine !== undefined && updates.engine !== form.engine) {
            updates = { ...updates, model: undefined };
        }
        setForm((prev) => {
            const updated = { ...prev, ...updates };
            runValidation(buildValidationData(updated, tools, envVars));
            return updated;
        });
        setDirty(true);
    };

    const addTool = (tool: string) => {
        if (tool && !tools.includes(tool)) {
            const newTools = [...tools, tool];
            setTools(newTools);
            setDirty(true);
            runValidation(buildValidationData(form, newTools, envVars));
        }
    };

    const removeTool = (tool: string) => {
        const newTools = tools.filter((t) => t !== tool);
        setTools(newTools);
        setDirty(true);
        runValidation(buildValidationData(form, newTools, envVars));
    };

    const replaceTools = (newTools: string[]) => {
        setTools(newTools);
        setDirty(true);
        runValidation(buildValidationData(form, newTools, envVars));
    };

    const handleSave = () => {
        setSaving(true);
        const envToSend = Object.keys(envVars).length > 0 ? envVars : undefined;
        const data = { ...form, allowedTools: tools, environment: envToSend };
        updateActionType(id, data)
            .then((updated) => {
                setActionType(updated);
                setDirty(false);
            })
            .catch(console.error)
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
                    <Button variant="secondary" icon={<MagicIcon />}
                        onClick={() => setAiModalOpen(true)}
                        style={{ marginRight: "8px" }}>
                        AI Assistant
                    </Button>
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

            <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                <Tab eventKey={0} title={<TabTitleText>Info</TabTitleText>}>
                    <TabContent id="info-tab" eventKey={0} activeKey={activeTab} style={{ marginTop: "24px" }}>
                        <InfoTab form={form} updateForm={updateForm} availableModels={availableModels} availableEngines={availableEngines} />
                    </TabContent>
                </Tab>
                {form.executionMode === "actor" && (
                    <Tab eventKey={1} title={<TabTitleText>Allowed Tools ({tools.length})</TabTitleText>}>
                        <TabContent id="tools-tab" eventKey={1} activeKey={activeTab} style={{ marginTop: "24px" }}>
                            <ToolListEditor
                                tools={tools}
                                onAdd={addTool}
                                onRemove={removeTool}
                                onReplace={replaceTools}
                                helpText={<>
                                    Define which tools the AI agent is allowed to use when performing this
                                    action type. Use patterns like <code>Bash(git log *)</code> to allow
                                    specific shell commands. Reference a toolset using{" "}
                                    <code>@ToolsetName</code> (e.g. <code>@Read-Only Tools</code>) to include
                                    all tools from that collection. Tools not in this list will be denied.
                                </>}
                                emptyContent={
                                    <EmptyState>
                                        <EmptyStateBody>
                                            No tools configured. The actor will use minimal read-only defaults.
                                        </EmptyStateBody>
                                    </EmptyState>
                                }
                            />
                        </TabContent>
                    </Tab>
                )}
                <Tab eventKey={2} title={<TabTitleText>
                    Environment{Object.keys(envVars).length > 0 ? ` (${Object.keys(envVars).length})` : ""}
                </TabTitleText>}>
                    <TabContent id="env-tab" eventKey={2} activeKey={activeTab} style={{ marginTop: "24px" }}>
                        <EnvironmentTab
                            envVars={envVars}
                            onChange={(updated) => {
                                setEnvVars(updated);
                                setDirty(true);
                                runValidation(buildValidationData(form, tools, updated));
                            }}
                        />
                    </TabContent>
                </Tab>
                {form.executionMode === "actor" && (
                    <Tab eventKey={3} title={<TabTitleText>Prompt Template</TabTitleText>}>
                        <TabContent id="prompt-tab" eventKey={3} activeKey={activeTab} style={{ marginTop: "24px" }}>
                            <PromptTemplateTab
                                value={form.promptTemplate || ""}
                                onChange={(v) => updateForm({ promptTemplate: v })}
                            />
                        </TabContent>
                    </Tab>
                )}
                {form.executionMode === "script" && (
                    <Tab eventKey={4} title={<TabTitleText>Script</TabTitleText>}>
                        <TabContent id="script-tab" eventKey={4} activeKey={activeTab} style={{ marginTop: "24px" }}>
                            <ScriptTab
                                value={form.scriptTemplate || ""}
                                onChange={(v) => updateForm({ scriptTemplate: v })}
                            />
                        </TabContent>
                    </Tab>
                )}
                {validationMessages.length > 0 && (
                    <Tab eventKey={5} title={
                        <TabTitleText>
                            {validationMessages.some((m) => m.severity === "error")
                                ? <ExclamationCircleIcon className="axiom-icon-danger" style={{ marginRight: 6 }} />
                                : <ExclamationTriangleIcon className="axiom-icon-warning" style={{ marginRight: 6 }} />
                            }
                            Problems ({validationMessages.length})
                        </TabTitleText>
                    }>
                        <TabContent id="problems-tab" eventKey={5} activeKey={activeTab} style={{ marginTop: "24px" }}>
                            <ValidationProblemsPanel messages={validationMessages} />
                        </TabContent>
                    </Tab>
                )}
            </Tabs>

            {form.executionMode === "script" && (
                <ScriptAiModal
                    isOpen={aiModalOpen}
                    script={form.scriptTemplate || ""}
                    actionTypeName={form.name}
                    actionTypeDescription={form.description}
                    onApply={(script) => {
                        updateForm({ scriptTemplate: script });
                    }}
                    onClose={() => setAiModalOpen(false)}
                />
            )}
            {form.executionMode === "actor" && (
                <ActionTypeAiModal
                    isOpen={aiModalOpen}
                    promptTemplate={form.promptTemplate || ""}
                    allowedTools={tools}
                    actionTypeName={form.name}
                    actionTypeDescription={form.description}
                    onApply={(prompt, newTools) => {
                        const updated = { ...form, promptTemplate: prompt };
                        setForm(updated);
                        setTools(newTools);
                        setDirty(true);
                        runValidation(buildValidationData(updated, newTools, envVars));
                    }}
                    onClose={() => setAiModalOpen(false)}
                />
            )}
        </PageSection>
    );
}

function InfoTab({ form, updateForm, availableModels, availableEngines }: {
    form: NewActionType;
    updateForm: (updates: Partial<NewActionType>) => void;
    availableModels: string[];
    availableEngines: string[];
}) {
    return (
        <Form style={{ maxWidth: "600px" }}>
            <FormGroup label="Name" isRequired fieldId="name">
                <TextInput
                    id="name"
                    isRequired
                    value={form.name}
                    onChange={(_e, v) => updateForm({ name: v })}
                />
            </FormGroup>
            <FormGroup label="Description" fieldId="description">
                <TextArea
                    id="description"
                    value={form.description || ""}
                    onChange={(_e, v) => updateForm({ description: v })}
                    rows={3}
                />
            </FormGroup>
            <FormGroup label="Execution Mode" isRequired fieldId="executionMode">
                <FormSelect
                    id="executionMode"
                    value={form.executionMode}
                    onChange={(_e, v) => updateForm({ executionMode: v })}
                >
                    <FormSelectOption value="actor" label="Actor — executed by an AI agent or human" />
                    <FormSelectOption value="script" label="Script — executes a bash script" />
                </FormSelect>
            </FormGroup>
            {form.executionMode === "actor" && availableEngines.length > 1 && (
                <FormGroup label="AI Engine" fieldId="engine">
                    <HelperText>
                        <HelperTextItem>AI engine to use for this action type. Select 'Global default' to use the system-wide setting.</HelperTextItem>
                    </HelperText>
                    <FormSelect
                        id="engine"
                        value={form.engine || ""}
                        onChange={(_e, v) => updateForm({ engine: v || undefined })}
                    >
                        <FormSelectOption value="" label="Global default" />
                        {availableEngines.map((e) => (
                            <FormSelectOption key={e} value={e} label={e === "opencode" ? "OpenCode" : e === "claude-code" ? "Claude Code" : e} />
                        ))}
                    </FormSelect>
                </FormGroup>
            )}
            {form.executionMode === "actor" && (
                <FormGroup label="Max Steps" fieldId="maxSteps">
                    <HelperText>
                        <HelperTextItem>Maximum number of agent steps/turns. Leave empty to use the global default.</HelperTextItem>
                    </HelperText>
                    <TextInput
                        id="maxSteps"
                        type="number"
                        value={form.maxSteps ?? ""}
                        onChange={(_e, v) => updateForm({ maxSteps: v === "" ? undefined : Number(v) })}
                        placeholder="Global default"
                    />
                </FormGroup>
            )}
            {form.executionMode === "actor" && (
                <FormGroup label="Max Budget (USD)" fieldId="maxBudgetUsd">
                    <HelperText>
                        <HelperTextItem>Maximum budget in USD for this action type. Leave empty to use the global default.</HelperTextItem>
                    </HelperText>
                    <TextInput
                        id="maxBudgetUsd"
                        type="number"
                        value={form.maxBudgetUsd ?? ""}
                        onChange={(_e, v) => updateForm({ maxBudgetUsd: v === "" ? undefined : Number(v) })}
                        placeholder="Global default"
                    />
                </FormGroup>
            )}
            {form.executionMode === "actor" && (
                <FormGroup label="Model" fieldId="model">
                    <HelperText>
                        <HelperTextItem>AI model to use for this action type. Select 'Global default' to use the system-wide setting.</HelperTextItem>
                    </HelperText>
                    <FormSelect
                        id="model"
                        value={form.model || ""}
                        onChange={(_e, v) => updateForm({ model: v || undefined })}
                    >
                        <FormSelectOption value="" label="Global default" />
                        {(() => {
                            // Group models by provider if they use provider/model format
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
                                            <FormSelectOption key={m} value={m} label={m.split("/").pop() || m} />
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
            )}
            {form.executionMode === "actor" && (
                <FormGroup label="Max Steps" fieldId="maxSteps">
                    <HelperText>
                        <HelperTextItem>Maximum number of agent steps/turns. Leave empty to use the global default.</HelperTextItem>
                    </HelperText>
                    <TextInput
                        id="maxSteps"
                        type="number"
                        value={form.maxSteps ?? ""}
                        onChange={(_e, v) => updateForm({ maxSteps: v === "" ? undefined : Number(v) })}
                        placeholder="Global default"
                    />
                </FormGroup>
            )}
            {form.executionMode === "actor" && (
                <FormGroup label="Max Budget (USD)" fieldId="maxBudgetUsd">
                    <HelperText>
                        <HelperTextItem>Maximum budget in USD for this action type. Leave empty to use the global default.</HelperTextItem>
                    </HelperText>
                    <TextInput
                        id="maxBudgetUsd"
                        type="number"
                        value={form.maxBudgetUsd ?? ""}
                        onChange={(_e, v) => updateForm({ maxBudgetUsd: v === "" ? undefined : Number(v) })}
                        placeholder="Global default"
                    />
                </FormGroup>
            )}
            <FormGroup fieldId="flags">
                <Checkbox
                    id="userTriggerable"
                    label="User triggerable — can be manually triggered from the project detail page"
                    isChecked={form.userTriggerable}
                    onChange={(_e, v) => updateForm({ userTriggerable: v })}
                />
                <Checkbox
                    id="managerTriggerable"
                    label="Manager triggerable — can be selected by the AI Manager during event triage"
                    isChecked={form.managerTriggerable}
                    onChange={(_e, v) => updateForm({ managerTriggerable: v })}
                />
                <Checkbox
                    id="emitsEvent"
                    label="Emits internal event on completion — allows the Manager to chain follow-up actions"
                    isChecked={form.emitsEvent}
                    onChange={(_e, v) => updateForm({ emitsEvent: v })}
                />
            </FormGroup>
        </Form>
    );
}

function PromptTemplateTab({ value, onChange }: {
    value: string;
    onChange: (v: string) => void;
}) {
    const effectiveTheme = useEffectiveTheme();
    return (
        <div>
            <p className="axiom-text-subtle" style={{ marginBottom: "16px" }}>
                The prompt sent to the AI agent when executing this action type.
                Supports placeholders:{" "}
                <code>{"{{managerInput}}"}</code>,{" "}
                <code>{"{{issueRef}}"}</code>,{" "}
                <code>{"{{repository}}"}</code>,{" "}
                <code>{"{{projectName}}"}</code>
            </p>
            <CodeEditor
                code={value}
                onCodeChange={(v) => onChange(v)}
                language={Language.markdown}
                height="500px"
                isDarkTheme={effectiveTheme === "dark"}
                isLineNumbersVisible
                onEditorDidMount={(editor, monaco) => {
                    registerPlaceholderCompletions(editor, monaco, "markdown", ACTION_TYPE_PLACEHOLDERS);
                }}
            />
        </div>
    );
}

function ScriptTab({ value, onChange }: {
    value: string;
    onChange: (v: string) => void;
}) {
    const effectiveTheme = useEffectiveTheme();
    return (
        <div>
            <p className="axiom-text-subtle" style={{ marginBottom: "16px" }}>
                A bash script that runs when this action type is triggered.
                Supports placeholders:{" "}
                <code>{"{{projectId}}"}</code>,{" "}
                <code>{"{{eventId}}"}</code>,{" "}
                <code>{"{{taskId}}"}</code>,{" "}
                <code>{"{{issueRef}}"}</code>,{" "}
                <code>{"{{repository}}"}</code>,{" "}
                <code>{"{{projectName}}"}</code>,{" "}
                <code>{"{{managerInput}}"}</code>,{" "}
                <code>{"{{apiBaseUrl}}"}</code>
            </p>
            <CodeEditor
                code={value}
                onCodeChange={(v) => onChange(v)}
                language={Language.shell}
                height="500px"
                isDarkTheme={effectiveTheme === "dark"}
                isLineNumbersVisible
            />
        </div>
    );
}

