import { useState, useEffect, useCallback } from "react";
import { useEffectiveTheme } from "../hooks/useTheme";
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
    FormSelect,
    FormSelectOption,
    ExpandableSection,
    Label,
    Modal,
    ModalBody,
    ModalHeader,
    PageSection,
    Tab,
    TabContent,
    TabTitleText,
    Tabs,
    TextArea,
    TextInput,
    Title, Content,
} from "@patternfly/react-core";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { markdownMermaidComponents } from "../components/MermaidBlock";
import SaveIcon from "@patternfly/react-icons/dist/esm/icons/save-icon";
import PlayIcon from "@patternfly/react-icons/dist/esm/icons/play-icon";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import MagicIcon from "@patternfly/react-icons/dist/esm/icons/magic-icon";
import {
    type ToolDefinition,
    type ToolParameter,
    type ToolTestResponse,
    type ToolValidationMessage,
    type NewToolDefinition,
    fetchTool,
    updateTool,
    testTool,
    validateTool,
} from "../config/api";
import ExclamationTriangleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-triangle-icon";
import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";
import { EditLabelsModal } from "../components/EditLabelsModal";
import { LabelDisplay } from "../components/LabelDisplay";
import { ToolAiModal } from "../components/ToolAiModal";
import { ValidationProblemsPanel } from "../components/ValidationProblemsPanel";

export function ToolDetailPage() {
    const { toolId } = useParams<{ toolId: string }>();
    const id = Number(toolId);

    const [tool, setTool] = useState<ToolDefinition | null>(null);
    const [form, setForm] = useState<NewToolDefinition>({ name: "" });
    const [params, setParams] = useState<ToolParameter[]>([]);
    const [labels, setLabels] = useState<string[]>([]);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [dirty, setDirty] = useState(false);
    const [activeTab, setActiveTab] = useState(0);
    const [aiModalOpen, setAiModalOpen] = useState(false);
    const [isLabelsOpen, setIsLabelsOpen] = useState(false);
    const [validationMessages, setValidationMessages] = useState<ToolValidationMessage[]>([]);

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

    const runValidation = useCallback((toolDef: NewToolDefinition) => {
        validateTool(toolDef)
            .then((result) => setValidationMessages(result.messages))
            .catch(console.error);
    }, []);

    const loadData = useCallback(() => {
        if (!id) return;
        setLoading(true);
        fetchTool(id)
            .then((t) => {
                setTool(t);
                setForm({
                    name: t.name, description: t.description,
                    scriptTemplate: t.scriptTemplate,
                });
                setParams(t.parameters || []);
                setLabels(t.labels || []);
                setDirty(false);
                runValidation({ ...t, parameters: t.parameters || [] });
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [id, runValidation]);

    useEffect(() => { loadData(); }, [loadData]);

    const updateForm = (updates: Partial<NewToolDefinition>) => {
        setForm((prev) => {
            const updated = { ...prev, ...updates };
            runValidation({ ...updated, parameters: params, labels });
            return updated;
        });
        setDirty(true);
    };

    const handleSave = () => {
        setSaving(true);
        const data = { ...form, parameters: params, labels };
        updateTool(id, data)
            .then((updated) => {
                setTool(updated);
                setDirty(false);
            })
            .catch(console.error)
            .finally(() => setSaving(false));
    };

    const addParam = () => {
        const newParams = [...params, { name: "", type: "string", description: "", required: true }];
        setParams(newParams);
        setDirty(true);
        runValidation({ ...form, parameters: newParams, labels });
    };

    const updateParam = (index: number, updates: Partial<ToolParameter>) => {
        const newParams = params.map((p, i) => i === index ? { ...p, ...updates } : p);
        setParams(newParams);
        setDirty(true);
        runValidation({ ...form, parameters: newParams, labels });
    };

    const removeParam = (index: number) => {
        const newParams = params.filter((_, i) => i !== index);
        setParams(newParams);
        setDirty(true);
        runValidation({ ...form, parameters: newParams, labels });
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
                    <Button
                        variant="tertiary"
                        icon={<MagicIcon />}
                        onClick={() => setAiModalOpen(true)}
                        style={{ marginLeft: "8px" }}
                    >
                        AI Assistant
                    </Button>
                </FlexItem>
            </Flex>

            <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                <Tab eventKey={0} title={<TabTitleText>Info</TabTitleText>}>
                    <TabContent id="info-tab" eventKey={0} activeKey={activeTab} style={{ marginTop: "24px" }}>
                        <InfoTab form={form} updateForm={updateForm}
                            labels={labels}
                            onEditLabels={() => setIsLabelsOpen(true)} />
                    </TabContent>
                </Tab>
                <Tab eventKey={1} title={<TabTitleText>Parameters ({params.length})</TabTitleText>}>
                    <TabContent id="params-tab" eventKey={1} activeKey={activeTab} style={{ marginTop: "24px" }}>
                        <ParametersTab
                            params={params}
                            addParam={addParam}
                            updateParam={updateParam}
                            removeParam={removeParam}
                        />
                    </TabContent>
                </Tab>
                <Tab eventKey={2} title={<TabTitleText>Script Template</TabTitleText>}>
                    <TabContent id="script-tab" eventKey={2} activeKey={activeTab} style={{ marginTop: "24px" }}>
                        <ScriptTemplateTab
                            value={form.scriptTemplate || ""}
                            onChange={(v) => updateForm({ scriptTemplate: v })}
                        />
                    </TabContent>
                </Tab>
                <Tab eventKey={3} title={<TabTitleText>Test</TabTitleText>}>
                    <TabContent id="test-tab" eventKey={3} activeKey={activeTab} style={{ marginTop: "24px" }}>
                        <TestTab toolId={id} params={params} scriptTemplate={form.scriptTemplate} />
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

            <ToolAiModal
                isOpen={aiModalOpen}
                form={form}
                params={params}
                onApply={(updates, newParams) => {
                    const updated = { ...form, ...updates, parameters: newParams, labels };
                    setForm((prev) => ({ ...prev, ...updates }));
                    setParams(newParams);
                    setDirty(true);
                    runValidation(updated);
                }}
                onClose={() => setAiModalOpen(false)}
            />

            <EditLabelsModal
                isOpen={isLabelsOpen}
                labels={labels}
                onSave={(newLabels) => { setLabels(newLabels); setDirty(true); }}
                onClose={() => setIsLabelsOpen(false)}
            />
        </PageSection>
    );
}

function InfoTab({ form, updateForm, labels, onEditLabels }: {
    form: NewToolDefinition;
    updateForm: (updates: Partial<NewToolDefinition>) => void;
    labels: string[];
    onEditLabels: () => void;
}) {
    return (
        <Form style={{ maxWidth: "600px" }}>
            <FormGroup label="Name" isRequired fieldId="name">
                <TextInput id="name" isRequired value={form.name}
                    onChange={(_e, v) => updateForm({ name: v })} />
            </FormGroup>

            <FormGroup label="Description" fieldId="description">
                <TextArea id="description" value={form.description || ""}
                    onChange={(_e, v) => updateForm({ description: v })} rows={3} />
            </FormGroup>

            <FormGroup label="Labels" fieldId="labels">
                <LabelDisplay labels={labels} onEdit={onEditLabels} />
            </FormGroup>
        </Form>
    );
}

function ParametersTab({ params, addParam, updateParam, removeParam }: {
    params: ToolParameter[];
    addParam: () => void;
    updateParam: (index: number, updates: Partial<ToolParameter>) => void;
    removeParam: (index: number) => void;
}) {
    return (
        <div style={{ maxWidth: "800px" }}>
            <p className="axiom-text-subtle" style={{ marginBottom: "16px" }}>
                Define the parameters the AI agent will provide when calling this tool.
                Use <code>{"{{param_name}}"}</code> in the script template to substitute values.
            </p>

            {params.length > 0 && (
                <Table aria-label="Parameters" variant="compact">
                    <Thead>
                        <Tr>
                            <Th>Name</Th>
                            <Th>Type</Th>
                            <Th>Description</Th>
                            <Th>Required</Th>
                            <Th screenReaderText="Actions" />
                        </Tr>
                    </Thead>
                    <Tbody>
                        {params.map((p, i) => (
                            <Tr key={i}>
                                <Td>
                                    <TextInput id={`param-name-${i}`}
                                        aria-label={`Parameter ${i} name`}
                                        value={p.name}
                                        onChange={(_e, v) => updateParam(i, { name: v })}
                                        placeholder="param_name" />
                                </Td>
                                <Td>
                                    <FormSelect id={`param-type-${i}`}
                                        aria-label={`Parameter ${i} type`}
                                        value={p.type}
                                        onChange={(_e, v) => updateParam(i, { type: v })}>
                                        <FormSelectOption value="string" label="string" />
                                        <FormSelectOption value="number" label="number" />
                                        <FormSelectOption value="boolean" label="boolean" />
                                    </FormSelect>
                                </Td>
                                <Td>
                                    <TextInput id={`param-desc-${i}`}
                                        aria-label={`Parameter ${i} description`}
                                        value={p.description || ""}
                                        onChange={(_e, v) => updateParam(i, { description: v })}
                                        placeholder="Description" />
                                </Td>
                                <Td>
                                    <input type="checkbox" checked={p.required || false}
                                        onChange={(e) => updateParam(i, { required: e.target.checked })} />
                                </Td>
                                <Td>
                                    <Button variant="plain" size="sm" onClick={() => removeParam(i)}>
                                        <TimesIcon />
                                    </Button>
                                </Td>
                            </Tr>
                        ))}
                    </Tbody>
                </Table>
            )}

            <Button variant="link" icon={<PlusCircleIcon />} onClick={addParam}
                style={{ marginTop: "8px" }}>
                Add Parameter
            </Button>
        </div>
    );
}

function ScriptTemplateTab({ value, onChange }: {
    value: string;
    onChange: (v: string) => void;
}) {
    const effectiveTheme = useEffectiveTheme();
    return (
        <div>
            <p className="axiom-text-subtle" style={{ marginBottom: "16px" }}>
                Bash script to execute when the tool is called. Supports multi-line scripts
                with variables, pipes, and control flow.
                Use <code>{"{{param_name}}"}</code> for parameter substitution.
                Use <code>{"{{param_name_file}}"}</code> to write the value to a temp file
                and substitute the file path (useful for long text like comment bodies).
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

function isJson(text: string): boolean {
    const trimmed = text.trim();
    if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return false;
    try {
        JSON.parse(trimmed);
        return true;
    } catch {
        return false;
    }
}

function formatJson(text: string): string {
    try {
        return JSON.stringify(JSON.parse(text.trim()), null, 2);
    } catch {
        return text;
    }
}

function TestTab({ toolId, params, scriptTemplate }: {
    toolId: number;
    params: ToolParameter[];
    scriptTemplate?: string;
}) {
    const effectiveTheme = useEffectiveTheme();
    const [paramValues, setParamValues] = useState<Record<string, string>>({});
    const [running, setRunning] = useState(false);
    const [result, setResult] = useState<ToolTestResponse | null>(null);
    const [resultOpen, setResultOpen] = useState(false);
    const [scriptExpanded, setScriptExpanded] = useState(false);

    if (!scriptTemplate || scriptTemplate.trim() === "") {
        return (
            <EmptyState>
                <EmptyStateBody>
                    Define a script template before testing this tool.
                </EmptyStateBody>
            </EmptyState>
        );
    }

    const handleRun = () => {
        setRunning(true);
        setResult(null);
        testTool(toolId, { scriptTemplate, parameters: paramValues })
            .then((r) => {
                setResult(r);
                setResultOpen(true);
                setScriptExpanded(false);
            })
            .catch((e) => {
                const errorResult: ToolTestResponse = {
                    success: false,
                    exitCode: 1,
                    output: "Request failed: " + e.message,
                    resolvedScript: "",
                    durationMs: 0,
                };
                setResult(errorResult);
                setResultOpen(true);
            })
            .finally(() => setRunning(false));
    };

    const outputIsJson = result?.output ? isJson(result.output) : false;

    return (
        <div style={{ maxWidth: "800px" }}>
            <p className="axiom-text-subtle" style={{ marginBottom: "16px" }}>
                Test this tool by providing parameter values and executing the script template.
                Secrets are not injected during testing.
            </p>

            {params.length > 0 && (
                <Form style={{ marginBottom: "24px" }}>
                    {params.map((p) => (
                        <FormGroup key={p.name} label={p.name} fieldId={`test-${p.name}`}
                            isRequired={p.required}>
                            <TextInput
                                id={`test-${p.name}`}
                                value={paramValues[p.name] || ""}
                                onChange={(_e, v) => setParamValues((prev) => ({ ...prev, [p.name]: v }))}
                                placeholder={p.description || `${p.type} value`}
                            />
                        </FormGroup>
                    ))}
                </Form>
            )}

            <Button variant="primary" icon={<PlayIcon />}
                onClick={handleRun}
                isDisabled={running}
                isLoading={running}>
                {running ? "Running..." : "Run Test"}
            </Button>

            {result && (
                <Modal isOpen={resultOpen}
                    onClose={() => setResultOpen(false)}
                    variant="large"
                    aria-label="Tool test results">
                    <ModalHeader title="Test Results" />
                    <ModalBody>
                        <Flex alignItems={{ default: "alignItemsCenter" }}
                            style={{ marginBottom: "16px", gap: "12px" }}>
                            <FlexItem>
                                <Label color={result.success ? "green" : "red"}>
                                    {result.success ? "Success" : "Failed"} (exit code {result.exitCode})
                                </Label>
                            </FlexItem>
                            <FlexItem>
                                <span className="axiom-text-subtle" style={{ fontSize: "13px" }}>
                                    {result.durationMs}ms
                                </span>
                            </FlexItem>
                        </Flex>

                        {result.resolvedScript && (
                            <ExpandableSection
                                toggleText={scriptExpanded ? "Hide resolved script" : "Show resolved script"}
                                isExpanded={scriptExpanded}
                                onToggle={(_e, expanded) => setScriptExpanded(expanded)}
                                style={{ marginBottom: "16px" }}
                            >
                                <CodeEditor
                                    code={result.resolvedScript}
                                    language={Language.shell}
                                    height="150px"
                                    isDarkTheme={effectiveTheme === "dark"}
                                    isReadOnly
                                    isLineNumbersVisible
                                />
                            </ExpandableSection>
                        )}

                        <div style={{ marginBottom: "8px", fontWeight: 600, fontSize: "14px" }}>Output</div>
                        {!result.output || result.output.trim() === "" ? (
                            <p className="axiom-text-subtle" style={{ fontStyle: "italic" }}>(no output)</p>
                        ) : outputIsJson ? (
                            <CodeEditor
                                code={formatJson(result.output)}
                                language={Language.json}
                                height="400px"
                                isDarkTheme={effectiveTheme === "dark"}
                                isReadOnly
                                isLineNumbersVisible
                            />
                        ) : (
                            <div style={{
                                padding: "16px",
                                backgroundColor: "var(--pf-t--global--background--color--secondary--default)",
                                borderRadius: "4px",
                                maxHeight: "400px",
                                overflow: "auto",
                            }}>
                                <Content>
                                    <Markdown remarkPlugins={[remarkGfm]} components={markdownMermaidComponents}>{result.output}</Markdown>
                                </Content>
                            </div>
                        )}
                    </ModalBody>
                </Modal>
            )}
        </div>
    );
}
