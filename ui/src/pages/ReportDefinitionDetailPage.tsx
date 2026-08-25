import { useState, useEffect, useCallback, useRef } from "react";
import { useEffectiveTheme } from "../hooks/useTheme";
import { useParams, useNavigate, Link } from "react-router-dom";
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
    FormSelect,
    FormSelectOption,
    PageSection,
    Switch,
    Tab,
    TabContent,
    TabTitleText,
    Tabs,
    TextArea,
    TextInput,
    Title, Alert,
    HelperText, HelperTextItem,
} from "@patternfly/react-core";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { registerPlaceholderCompletions, REPORT_PLACEHOLDERS } from "../components/PlaceholderCompletionProvider";
import { EnvironmentTab } from "../components/EnvironmentTab";
import { ToolListEditor } from "../components/ToolListEditor";
import { LabelInput } from "../components/LabelInput";
import { ReportAiModal } from "../components/ReportAiModal";
import { ValidationProblemsPanel } from "../components/ValidationProblemsPanel";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import MagicIcon from "@patternfly/react-icons/dist/esm/icons/magic-icon";
import PlayIcon from "@patternfly/react-icons/dist/esm/icons/play-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {
    type ReportDefinition,
    type NewReportDefinition,
    type ToolValidationMessage,
    fetchReportDefinition,
    updateReportDefinition,
    runReportDefinition,
    deleteReportDefinition,
    validateReportDefinition,
} from "../config/api";
import ExclamationTriangleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-triangle-icon";
import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";

function slugify(name: string | undefined): string {
    if (!name) return "";
    return name.trim().toLowerCase().replace(/\s+/g, "-").replace(/[^a-z0-9-]/g, "").replace(/-{2,}/g, "-");
}

export function ReportDefinitionDetailPage() {
    const { definitionId } = useParams<{ definitionId: string }>();
    const navigate = useNavigate();
    const id = Number(definitionId);

    const [definition, setDefinition] = useState<ReportDefinition | null>(null);
    const [form, setForm] = useState<NewReportDefinition>({
        name: "", schedule: "daily", promptTemplate: "", enabled: false,
    });
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [activeTab, setActiveTab] = useState(0);

    const [aiModalOpen, setAiModalOpen] = useState(false);
    const [isDeleteOpen, setIsDeleteOpen] = useState(false);
    const [tools, setTools] = useState<string[]>([]);
    const [initialLabels, setInitialLabels] = useState<string[]>([]);
    const [envVars, setEnvVars] = useState<Record<string, string>>({});
    const [validationMessages, setValidationMessages] = useState<ToolValidationMessage[]>([]);

    const validationTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

    const runValidation = useCallback((defData: NewReportDefinition) => {
        if (validationTimerRef.current) {
            clearTimeout(validationTimerRef.current);
        }
        validationTimerRef.current = setTimeout(() => {
            validateReportDefinition(defData)
                .then((result) => setValidationMessages(result.messages))
                .catch(console.error);
        }, 500);
    }, []);

    const loadData = useCallback(() => {
        if (!id) return;
        setLoading(true);
        fetchReportDefinition(id)
            .then((def) => {
                setDefinition(def);
                setForm({
                    name: def.name, description: def.description,
                    slug: def.slug,
                    schedule: def.schedule, scheduleTime: def.scheduleTime,
                    scheduleDayOfWeek: def.scheduleDayOfWeek,
                    timeWindow: def.timeWindow,
                    promptTemplate: def.promptTemplate, enabled: def.enabled,
                    timeoutSeconds: def.timeoutSeconds,
                    titleTemplate: def.titleTemplate,
                });
                setTools(def.allowedTools || []);
                setInitialLabels(def.initialLabels || []);
                setEnvVars(def.environment || {});
                setDirty(false);
                runValidation(buildValidationData(
                    def, def.allowedTools || [], def.initialLabels || [],
                    def.environment || {}
                ));
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [id, runValidation]);

    useEffect(() => { loadData(); }, [loadData]);

    const buildValidationData = (
        formData: NewReportDefinition,
        toolsList: string[],
        labelsList: string[],
        env: Record<string, string>
    ): NewReportDefinition => ({
        ...formData,
        allowedTools: toolsList,
        initialLabels: labelsList,
        environment: Object.keys(env).length > 0 ? env : undefined,
    });

    const updateForm = (updates: Partial<NewReportDefinition>) => {
        setForm((prev) => {
            const updated = { ...prev, ...updates };
            runValidation(buildValidationData(updated, tools, initialLabels, envVars));
            return updated;
        });
        setDirty(true);
    };

    const handleSave = () => {
        setSaving(true);
        const envToSend = Object.keys(envVars).length > 0 ? envVars : undefined;
        const data = {
            ...form,
            allowedTools: tools.length > 0 ? tools : undefined,
            initialLabels: initialLabels,
            environment: envToSend,
        };
        updateReportDefinition(id, data)
            .then((updated) => { setDefinition(updated); setDirty(false); })
            .catch(console.error)
            .finally(() => setSaving(false));
    };

    const addTool = (tool: string) => {
        if (tool && !tools.includes(tool)) {
            const newTools = [...tools, tool];
            setTools(newTools);
            setDirty(true);
            runValidation(buildValidationData(form, newTools, initialLabels, envVars));
        }
    };

    const removeTool = (tool: string) => {
        const newTools = tools.filter((t) => t !== tool);
        setTools(newTools);
        setDirty(true);
        runValidation(buildValidationData(form, newTools, initialLabels, envVars));
    };

    const replaceTools = (newTools: string[]) => {
        setTools(newTools);
        setDirty(true);
        runValidation(buildValidationData(form, newTools, initialLabels, envVars));
    };

    const handleRunNow = () => {
        runReportDefinition(id)
            .then((report) => navigate(`/reports/${report.id}`))
            .catch(console.error);
    };

    const handleDelete = () => {
        deleteReportDefinition(id)
            .then(() => navigate("/report-definitions"))
            .catch(console.error);
    };

    if (loading) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Loading...</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    if (!definition) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Report definition not found.</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: "16px" }}>
                <BreadcrumbItem><Link to="/report-definitions">Report Definitions</Link></BreadcrumbItem>
                <BreadcrumbItem isActive>{definition.name}</BreadcrumbItem>
            </Breadcrumb>

            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}>
                <FlexItem>
                    <Title headingLevel="h1" size="lg">{definition.name}</Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="secondary" icon={<PlayIcon />} onClick={handleRunNow}
                        style={{ marginRight: "8px" }}>
                        Run Now
                    </Button>
                    <Button variant="secondary" icon={<MagicIcon />}
                            onClick={() => setAiModalOpen(true)}
                            style={{ marginRight: "8px" }}>
                        AI Assistant
                    </Button>
                    <Button variant="primary" icon={<SaveIcon />} onClick={handleSave}
                        isDisabled={!dirty || !form.name || saving || validationMessages.some((m) => m.severity === "error")} isLoading={saving}
                        style={{ marginRight: "8px" }}>
                        {saving ? "Saving..." : "Save Changes"}
                    </Button>
                    <Button variant="danger" icon={<TrashIcon />}
                        onClick={() => setIsDeleteOpen(true)}>
                        Delete
                    </Button>
                </FlexItem>
            </Flex>

            <ReportAiModal
                isOpen={aiModalOpen}
                promptTemplate={form.promptTemplate || ""}
                allowedTools={tools}
                reportName={form.name}
                reportDescription={form.description}
                onApply={(prompt, newTools) => {
                    const updated = { ...form, promptTemplate: prompt };
                    setForm(updated);
                    setTools(newTools);
                    setDirty(true);
                    runValidation(buildValidationData(updated, newTools, initialLabels, envVars));
                }}
                onClose={() => setAiModalOpen(false)}
            />

            <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                <Tab eventKey={0} title={<TabTitleText>Info</TabTitleText>}>
                    <TabContent id="info-tab" eventKey={0} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <InfoTab form={form} updateForm={updateForm}
                            initialLabels={initialLabels}
                            onLabelsChange={(labels) => { setInitialLabels(labels); setDirty(true); }} />
                    </TabContent>
                </Tab>
                <Tab eventKey={1} title={<TabTitleText>Allowed Tools ({tools.length})</TabTitleText>}>
                    <TabContent id="tools-tab" eventKey={1} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <ToolListEditor
                            tools={tools}
                            onAdd={addTool}
                            onRemove={removeTool}
                            onReplace={replaceTools}
                            helpText={<>
                                Define which tools the AI agent is allowed to use when generating this
                                report. Use patterns like <code>Bash(gh issue *)</code> for specific
                                shell commands and <code>mcp__axiom-tools__*</code> for MCP tools.
                                Reference a toolset using <code>@ToolsetName</code> (e.g.{" "}
                                <code>@Report Tools</code>) to include all tools from that collection.
                            </>}
                            emptyContent={
                                <Alert variant="info" title="No tools configured. A default set of read-only tools will be used." ouiaId="InfoAlert" />
                            }
                        />
                    </TabContent>
                </Tab>
                <Tab eventKey={2} title={<TabTitleText>
                    Environment{Object.keys(envVars).length > 0 ? ` (${Object.keys(envVars).length})` : ""}
                </TabTitleText>}>
                    <TabContent id="env-tab" eventKey={2} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <EnvironmentTab
                            envVars={envVars}
                            onChange={(updated) => {
                                setEnvVars(updated);
                                setDirty(true);
                                runValidation(buildValidationData(form, tools, initialLabels, updated));
                            }}
                        />
                    </TabContent>
                </Tab>
                <Tab eventKey={3} title={<TabTitleText>Prompt Template</TabTitleText>}>
                    <TabContent id="prompt-tab" eventKey={3} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <PromptTemplateTab
                            value={form.promptTemplate}
                            onChange={(v) => updateForm({ promptTemplate: v })}
                        />
                    </TabContent>
                </Tab>
                {validationMessages.length > 0 && (
                    <Tab eventKey={4} title={
                        <TabTitleText>
                            {validationMessages.some((m) => m.severity === "error")
                                ? <ExclamationCircleIcon className="axiom-icon-danger" style={{ marginRight: 6 }} />
                                : <ExclamationTriangleIcon className="axiom-icon-warning" style={{ marginRight: 6 }} />
                            }
                            Problems ({validationMessages.length})
                        </TabTitleText>
                    }>
                        <TabContent id="problems-tab" eventKey={4} activeKey={activeTab} style={{ marginTop: "24px" }}>
                            <ValidationProblemsPanel messages={validationMessages} />
                        </TabContent>
                    </Tab>
                )}
            </Tabs>

            <ConfirmDeleteModal isOpen={isDeleteOpen} title="Delete Report Definition"
                onConfirm={handleDelete} onCancel={() => setIsDeleteOpen(false)}>
                Are you sure you want to delete this report definition and all its
                generated reports? This action cannot be undone.
            </ConfirmDeleteModal>
        </PageSection>
    );
}

function InfoTab({ form, updateForm, initialLabels, onLabelsChange }: {
    form: NewReportDefinition;
    updateForm: (updates: Partial<NewReportDefinition>) => void;
    initialLabels: string[];
    onLabelsChange: (labels: string[]) => void;
}) {
    return (
        <Form style={{ maxWidth: "600px" }}>
            <FormGroup label="Name" isRequired fieldId="name">
                <TextInput id="name" isRequired value={form.name}
                    onChange={(_e, v) => updateForm({ name: v })} />
            </FormGroup>
            <FormGroup label="Slug" fieldId="slug">
                <TextInput id="slug" value={form.slug || ""}
                    onChange={(_e, v) => updateForm({ slug: v })}
                    placeholder={slugify(form.name)} />
                <HelperText>
                    <HelperTextItem>
                        Stable identifier used for agent capability matching.
                        Auto-generated from name if empty.
                    </HelperTextItem>
                </HelperText>
            </FormGroup>
            <FormGroup label="Title Template" fieldId="titleTemplate">
                <TextInput id="titleTemplate" value={form.titleTemplate || ""}
                    onChange={(_e, v) => updateForm({ titleTemplate: v || undefined })}
                    placeholder="e.g. {{name}} — {{date}}" />
                <p className="axiom-text-subtle" style={{ fontSize: "0.85em", marginTop: "4px" }}>
                    Optional. If set, the report title uses this template instead of
                    extracting from markdown. Placeholders:{" "}
                    <code>{"{{name}}"}</code>, <code>{"{{date}}"}</code>,{" "}
                    <code>{"{{time}}"}</code>, <code>{"{{datetime}}"}</code>,{" "}
                    <code>{"{{timeWindow}}"}</code>, <code>{"{{schedule}}"}</code>
                </p>
            </FormGroup>
            <FormGroup label="Description" fieldId="description">
                <TextArea id="description" value={form.description || ""}
                    onChange={(_e, v) => updateForm({ description: v })} rows={3} />
            </FormGroup>
            <FormGroup label="Schedule" isRequired fieldId="schedule">
                <FormSelect id="schedule" value={form.schedule}
                    onChange={(_e, v) => updateForm({ schedule: v })}>
                    <FormSelectOption value="none" label="Not Scheduled (ad hoc only)" />
                    <FormSelectOption value="hourly" label="Hourly" />
                    <FormSelectOption value="daily" label="Daily" />
                    <FormSelectOption value="weekly" label="Weekly" />
                    <FormSelectOption value="monthly" label="Monthly" />
                </FormSelect>
            </FormGroup>
            {form.schedule === "weekly" && (
                <FormGroup label="Day of Week" fieldId="scheduleDayOfWeek">
                    <FormSelect id="scheduleDayOfWeek"
                        value={form.scheduleDayOfWeek || ""}
                        onChange={(_e, v) => updateForm({ scheduleDayOfWeek: v || undefined })}>
                        <FormSelectOption value="" label="Same day each week" />
                        <FormSelectOption value="monday" label="Monday" />
                        <FormSelectOption value="tuesday" label="Tuesday" />
                        <FormSelectOption value="wednesday" label="Wednesday" />
                        <FormSelectOption value="thursday" label="Thursday" />
                        <FormSelectOption value="friday" label="Friday" />
                        <FormSelectOption value="saturday" label="Saturday" />
                        <FormSelectOption value="sunday" label="Sunday" />
                    </FormSelect>
                </FormGroup>
            )}
            {form.schedule !== "none" && (
                <FormGroup label="Time of Day" fieldId="scheduleTime">
                    <TextInput id="scheduleTime" value={form.scheduleTime || ""}
                        onChange={(_e, v) => updateForm({ scheduleTime: v })}
                        placeholder="08:00" />
                </FormGroup>
            )}
            <FormGroup label="Time Window" fieldId="timeWindow">
                <FormSelect id="timeWindow" value={form.timeWindow || ""}
                    onChange={(_e, v) => updateForm({ timeWindow: v || undefined })}>
                    <FormSelectOption value="" label="None" />
                    <FormSelectOption value="since-last-run" label="Since Last Run" />
                    <FormSelectOption value="last-24h" label="Last 24 Hours" />
                    <FormSelectOption value="last-7d" label="Last 7 Days" />
                    <FormSelectOption value="last-30d" label="Last 30 Days" />
                </FormSelect>
            </FormGroup>
            <FormGroup label="Timeout (seconds)" fieldId="timeoutSeconds">
                <TextInput id="timeoutSeconds" type="number"
                    value={form.timeoutSeconds?.toString() || ""}
                    onChange={(_e, v) => updateForm({ timeoutSeconds: v ? parseInt(v) : undefined })}
                    placeholder="Global default (600)" />
            </FormGroup>
            <FormGroup label="Labels" fieldId="initialLabels">
                <LabelInput labels={initialLabels}
                    onChange={onLabelsChange} />
            </FormGroup>
            {form.schedule !== "none" && (
                <FormGroup fieldId="enabled">
                    <Switch id="enabled" label="Enabled — report will run automatically on schedule"
                        isChecked={form.enabled}
                        onChange={(_e, v) => updateForm({ enabled: v })} />
                </FormGroup>
            )}
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
                Instructions for the AI agent when generating this report.
                Supports placeholders:{" "}
                <code>{"{{repositories}}"}</code>,{" "}
                <code>{"{{timeRangeStart}}"}</code>,{" "}
                <code>{"{{timeRangeEnd}}"}</code>,{" "}
                <code>{"{{timeWindow}}"}</code>
            </p>
            <CodeEditor
                code={value}
                onCodeChange={(v) => onChange(v)}
                language={Language.markdown}
                height="400px"
                isDarkTheme={effectiveTheme === "dark"}
                isLineNumbersVisible
                onEditorDidMount={(editor, monaco) => {
                    registerPlaceholderCompletions(editor, monaco, "markdown", REPORT_PLACEHOLDERS);
                }}
            />
        </div>
    );
}



