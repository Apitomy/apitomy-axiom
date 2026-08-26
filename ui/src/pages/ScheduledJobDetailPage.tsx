import { useState, useEffect, useCallback, Fragment } from "react";
import { useEffectiveTheme } from "../hooks/useTheme";
import { useParams, useNavigate, Link } from "react-router-dom";
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
    FormSelect,
    FormSelectOption,
    HelperText,
    HelperTextItem,
    Label,
    PageSection,
    Switch,
    Tab,
    TabContent,
    TabTitleText,
    Tabs,
    TextArea,
    TextInput,
    Title,
} from "@patternfly/react-core";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import { ConfirmDeleteModal } from "../components/ConfirmDeleteModal";
import { AiConfigTab } from "../components/AiConfigTab";
import { EnvironmentTab } from "../components/EnvironmentTab";
import { ToolListEditor } from "../components/ToolListEditor";
import { LabelInput } from "../components/LabelInput";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import PlayIcon from "@patternfly/react-icons/dist/esm/icons/play-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import {
    type ScheduledJob,
    type NewScheduledJob,
    type ScheduledJobRun,
    fetchScheduledJob,
    updateScheduledJob,
    deleteScheduledJob,
    runScheduledJob,
    fetchScheduledJobRuns,
    fetchModels,
    fetchEngines,
} from "../config/api";

const RUN_STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    "Pending": "grey",
    "Running": "blue",
    "Completed": "green",
    "Failed": "red",
};

function formatDuration(ms: number): string {
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
    const mins = Math.floor(ms / 60_000);
    const secs = Math.round((ms % 60_000) / 1000);
    return `${mins}m ${secs}s`;
}

function formatCost(cost?: number): string {
    return cost != null ? `$${cost.toFixed(4)}` : "--";
}

function formatTimestamp(iso?: string): string {
    if (!iso) return "--";
    const d = new Date(iso);
    return d.toLocaleString();
}

function slugify(name: string | undefined): string {
    if (!name) return "";
    return name.trim().toLowerCase().replace(/\s+/g, "-").replace(/[^a-z0-9-]/g, "").replace(/-{2,}/g, "-");
}

export function ScheduledJobDetailPage() {
    const { jobId } = useParams<{ jobId: string }>();
    const navigate = useNavigate();
    const id = Number(jobId);

    const [job, setJob] = useState<ScheduledJob | null>(null);
    const [form, setForm] = useState<NewScheduledJob>({
        name: "",
        description: "",
        enabled: false,
        schedule: "none",
        executionMode: "agent",
    });
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [activeTab, setActiveTab] = useState(0);
    const [isDeleteOpen, setIsDeleteOpen] = useState(false);

    const [runs, setRuns] = useState<ScheduledJobRun[]>([]);
    const [expandedRunId, setExpandedRunId] = useState<number | null>(null);

    const [tools, setTools] = useState<string[]>([]);
    const [labels, setLabels] = useState<string[]>([]);
    const [envVars, setEnvVars] = useState<Record<string, string>>({});

    const [availableModels, setAvailableModels] = useState<string[]>([]);
    const [availableEngines, setAvailableEngines] = useState<string[]>([]);

    const loadData = useCallback(() => {
        if (!id) return;
        setLoading(true);
        Promise.all([
            fetchScheduledJob(id),
            fetchScheduledJobRuns(id, 1, 10),
        ])
            .then(([jobData, runsData]) => {
                setJob(jobData);
                setForm({
                    name: jobData.name,
                    description: jobData.description,
                    slug: jobData.slug,
                    enabled: jobData.enabled,
                    schedule: jobData.schedule,
                    scheduleTime: jobData.scheduleTime,
                    scheduleDayOfWeek: jobData.scheduleDayOfWeek,
                    executionMode: jobData.executionMode,
                    promptTemplate: jobData.promptTemplate,
                    scriptTemplate: jobData.scriptTemplate,
                    model: jobData.model,
                    engine: jobData.engine,
                    maxSteps: jobData.maxSteps,
                    maxBudgetUsd: jobData.maxBudgetUsd,
                });
                setTools(jobData.allowedTools || []);
                setLabels(jobData.labels || []);
                setEnvVars(jobData.environment || {});
                setRuns(runsData.items);
                setDirty(false);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [id]);

    useEffect(() => { loadData(); }, [loadData]);

    useEffect(() => {
        fetchEngines().then(setAvailableEngines).catch(console.error);
    }, []);

    useEffect(() => {
        fetchModels(form.engine || undefined).then(setAvailableModels).catch(console.error);
    }, [form.engine]);

    const updateForm = (updates: Partial<NewScheduledJob>) => {
        if (updates.engine !== undefined && updates.engine !== form.engine) {
            updates = { ...updates, model: undefined };
        }
        setForm((prev) => ({ ...prev, ...updates }));
        setDirty(true);
    };

    const handleSave = () => {
        setSaving(true);
        const envToSend = Object.keys(envVars).length > 0 ? envVars : undefined;
        const data: NewScheduledJob = {
            ...form,
            allowedTools: tools.length > 0 ? tools : undefined,
            labels: labels.length > 0 ? labels : undefined,
            environment: envToSend,
        };
        updateScheduledJob(id, data)
            .then((updated) => {
                setJob(updated);
                setDirty(false);
            })
            .catch(console.error)
            .finally(() => setSaving(false));
    };

    const handleRunNow = () => {
        runScheduledJob(id)
            .then(() => {
                fetchScheduledJobRuns(id, 1, 10)
                    .then((runsData) => setRuns(runsData.items))
                    .catch(console.error);
            })
            .catch(console.error);
    };

    const handleDelete = () => {
        deleteScheduledJob(id)
            .then(() => navigate("/scheduled-jobs"))
            .catch(console.error);
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

    if (loading) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Loading scheduled job...</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    if (!job) {
        return (
            <PageSection>
                <EmptyState><EmptyStateBody>Scheduled job not found.</EmptyStateBody></EmptyState>
            </PageSection>
        );
    }

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: "16px" }}>
                <BreadcrumbItem><Link to="/scheduled-jobs">Scheduled Jobs</Link></BreadcrumbItem>
                <BreadcrumbItem isActive>{job.name}</BreadcrumbItem>
            </Breadcrumb>

            <Flex
                justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}
            >
                <FlexItem>
                    <Title headingLevel="h1" size="lg">{job.name}</Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="secondary" icon={<PlayIcon />} onClick={handleRunNow}
                        style={{ marginRight: "8px" }}>
                        Run Now
                    </Button>
                    <Button variant="primary" icon={<SaveIcon />} onClick={handleSave}
                        isDisabled={!dirty || !form.name || saving}
                        isLoading={saving}
                        style={{ marginRight: "8px" }}>
                        {saving ? "Saving..." : "Save Changes"}
                    </Button>
                    <Button variant="danger" icon={<TrashIcon />}
                        onClick={() => setIsDeleteOpen(true)}>
                        Delete
                    </Button>
                </FlexItem>
            </Flex>

            <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                <Tab eventKey={0} title={<TabTitleText>Info</TabTitleText>}>
                    <TabContent id="info-tab" eventKey={0} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <InfoTab form={form} updateForm={updateForm}
                            labels={labels}
                            onLabelsChange={(l) => { setLabels(l); setDirty(true); }} />
                    </TabContent>
                </Tab>
                <Tab eventKey={1} title={<TabTitleText>Allowed Tools ({tools.length})</TabTitleText>}>
                    <TabContent id="tools-tab" eventKey={1} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        {form.executionMode === "agent" ? (
                            <ToolListEditor
                                tools={tools}
                                onAdd={addTool}
                                onRemove={removeTool}
                                onReplace={replaceTools}
                                helpText={<>
                                    Define which tools the AI agent is allowed to use when running
                                    this job. Use patterns like <code>Bash(gh issue *)</code> for
                                    specific shell commands and <code>mcp__axiom-tools__*</code> for
                                    MCP tools. Reference a toolset using <code>@ToolsetName</code>{" "}
                                    (e.g. <code>@Report Tools</code>) to include all tools from that
                                    collection.
                                </>}
                                emptyContent={
                                    <Alert variant="info"
                                        title="No tools configured. A default set of tools will be used."
                                        ouiaId="InfoAlert" />
                                }
                            />
                        ) : (
                            <EmptyState>
                                <EmptyStateBody>
                                    Tool configuration is only available for Agent execution mode.
                                </EmptyStateBody>
                            </EmptyState>
                        )}
                    </TabContent>
                </Tab>
                <Tab eventKey={2} title={<TabTitleText>
                    Environment{Object.keys(envVars).length > 0
                        ? ` (${Object.keys(envVars).length})` : ""}
                </TabTitleText>}>
                    <TabContent id="env-tab" eventKey={2} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <EnvironmentTab
                            envVars={envVars}
                            onChange={(updated) => {
                                setEnvVars(updated);
                                setDirty(true);
                            }}
                        />
                    </TabContent>
                </Tab>
                <Tab eventKey={3} title={<TabTitleText>
                    {form.executionMode === "script" ? "Script" : "Prompt Template"}
                </TabTitleText>}>
                    <TabContent id="template-tab" eventKey={3} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        {form.executionMode === "agent" ? (
                            <PromptTemplateTab
                                value={form.promptTemplate || ""}
                                onChange={(v) => updateForm({ promptTemplate: v })}
                            />
                        ) : (
                            <ScriptTab
                                value={form.scriptTemplate || ""}
                                onChange={(v) => updateForm({ scriptTemplate: v })}
                            />
                        )}
                    </TabContent>
                </Tab>
                {form.executionMode === "agent" && (
                    <Tab eventKey={10} title={<TabTitleText>AI Config</TabTitleText>}>
                        <TabContent id="ai-config-tab" eventKey={10} activeKey={activeTab}
                            style={{ marginTop: "24px" }}>
                            <Form style={{ maxWidth: "600px" }}>
                                <AiConfigTab
                                    values={form}
                                    onChange={updateForm}
                                    availableEngines={availableEngines}
                                    availableModels={availableModels}
                                />
                            </Form>
                        </TabContent>
                    </Tab>
                )}
                <Tab eventKey={4} title={<TabTitleText>
                    Run History{runs.length > 0 ? ` (${runs.length})` : ""}
                </TabTitleText>}>
                    <TabContent id="history-tab" eventKey={4} activeKey={activeTab}
                        style={{ marginTop: "24px" }}>
                        <RunHistorySection runs={runs} expandedRunId={expandedRunId}
                            onToggleExpand={(runId) =>
                                setExpandedRunId(expandedRunId === runId ? null : runId)} />
                    </TabContent>
                </Tab>
            </Tabs>

            <ConfirmDeleteModal isOpen={isDeleteOpen} title="Delete Scheduled Job"
                onConfirm={handleDelete} onCancel={() => setIsDeleteOpen(false)}>
                Are you sure you want to delete this scheduled job and all its
                run history? This action cannot be undone.
            </ConfirmDeleteModal>
        </PageSection>
    );
}

function InfoTab({ form, updateForm, labels, onLabelsChange }: {
    form: NewScheduledJob;
    updateForm: (updates: Partial<NewScheduledJob>) => void;
    labels: string[];
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
            {form.schedule !== "none" && form.schedule !== "hourly" && (
                <FormGroup label="Time of Day" fieldId="scheduleTime">
                    <TextInput id="scheduleTime" value={form.scheduleTime || ""}
                        onChange={(_e, v) => updateForm({ scheduleTime: v })}
                        placeholder="08:00" />
                </FormGroup>
            )}
            <FormGroup label="Execution Mode" isRequired fieldId="executionMode">
                <FormSelect id="executionMode" value={form.executionMode}
                    onChange={(_e, v) => updateForm({ executionMode: v })}>
                    <FormSelectOption value="agent" label="Agent — executed by an AI agent" />
                    <FormSelectOption value="script" label="Script — executes a bash script" />
                </FormSelect>
            </FormGroup>
            <FormGroup label="Labels" fieldId="labels">
                <LabelInput labels={labels} onChange={onLabelsChange} />
            </FormGroup>
            {form.schedule !== "none" && (
                <FormGroup fieldId="enabled">
                    <Switch id="enabled-toggle"
                        label="Enabled — job will run automatically on schedule"
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
                Instructions for the AI agent when executing this scheduled job.
            </p>
            <CodeEditor
                code={value}
                onCodeChange={(v) => onChange(v)}
                language={Language.markdown}
                height="400px"
                isDarkTheme={effectiveTheme === "dark"}
                isLineNumbersVisible
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
                Bash script to execute when this scheduled job runs.
            </p>
            <CodeEditor
                code={value}
                onCodeChange={(v) => onChange(v)}
                language={Language.shell}
                height="400px"
                isDarkTheme={effectiveTheme === "dark"}
                isLineNumbersVisible
            />
        </div>
    );
}

function RunHistorySection({ runs, expandedRunId, onToggleExpand }: {
    runs: ScheduledJobRun[];
    expandedRunId: number | null;
    onToggleExpand: (runId: number) => void;
}) {
    if (runs.length === 0) {
        return (
            <EmptyState>
                <EmptyStateBody>
                    No runs yet. Use &ldquo;Run Now&rdquo; to trigger the first execution.
                </EmptyStateBody>
            </EmptyState>
        );
    }

    return (
        <Table aria-label="Run History" variant="compact">
            <Thead>
                <Tr>
                    <Th>Status</Th>
                    <Th>Trigger</Th>
                    <Th>Started At</Th>
                    <Th>Duration</Th>
                    <Th>Cost</Th>
                </Tr>
            </Thead>
            <Tbody>
                {runs.map((run) => (
                    <Fragment key={run.id}>
                        <Tr
                            isClickable
                            onRowClick={() => onToggleExpand(run.id)}
                            isRowSelected={expandedRunId === run.id}>
                            <Td>
                                <Label isCompact
                                    color={RUN_STATUS_COLORS[run.status] || "grey"}>
                                    {run.status}
                                </Label>
                            </Td>
                            <Td>{run.trigger}</Td>
                            <Td style={{ whiteSpace: "nowrap" }}>
                                {formatTimestamp(run.startedAt)}
                            </Td>
                            <Td style={{ whiteSpace: "nowrap" }}>
                                {run.durationMs != null ? formatDuration(run.durationMs) : "--"}
                            </Td>
                            <Td>{formatCost(run.costUsd)}</Td>
                        </Tr>
                        {expandedRunId === run.id && (
                            <Tr key={`${run.id}-detail`}>
                                <Td colSpan={5}>
                                    <RunDetail run={run} />
                                </Td>
                            </Tr>
                        )}
                    </Fragment>
                ))}
            </Tbody>
        </Table>
    );
}

function RunDetail({ run }: { run: ScheduledJobRun }) {
    return (
        <div style={{ padding: "16px" }}>
            {run.output && (
                <div style={{ marginBottom: "12px" }}>
                    <Title headingLevel="h4" size="md">Output</Title>
                    <pre style={{
                        whiteSpace: "pre-wrap",
                        wordBreak: "break-word",
                        background: "var(--pf-v5-global--BackgroundColor--200)",
                        padding: "12px",
                        borderRadius: "4px",
                        maxHeight: "300px",
                        overflow: "auto",
                    }}>
                        {run.output}
                    </pre>
                </div>
            )}
            {run.error && (
                <div style={{ marginBottom: "12px" }}>
                    <Title headingLevel="h4" size="md">Error</Title>
                    <pre style={{
                        whiteSpace: "pre-wrap",
                        wordBreak: "break-word",
                        background: "var(--pf-v5-global--BackgroundColor--200)",
                        padding: "12px",
                        borderRadius: "4px",
                        color: "var(--pf-v5-global--danger-color--100)",
                        maxHeight: "300px",
                        overflow: "auto",
                    }}>
                        {run.error}
                    </pre>
                </div>
            )}
            {!run.output && !run.error && (
                <p className="axiom-text-subtle">
                    No output or error details available for this run.
                </p>
            )}
        </div>
    );
}
