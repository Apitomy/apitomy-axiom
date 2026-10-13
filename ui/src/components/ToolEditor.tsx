import { useState, useEffect, useRef } from "react";
import { useEffectiveTheme } from "../hooks/useTheme";
import {
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
    HelperText,
    HelperTextItem,
    Label,
    Modal,
    ModalBody,
    ModalHeader,
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
import { markdownMermaidComponents } from "./MermaidBlock";
import PlayIcon from "@patternfly/react-icons/dist/esm/icons/play-icon";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import TrashIcon from "@patternfly/react-icons/dist/esm/icons/trash-icon";
import ExclamationTriangleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-triangle-icon";
import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";
import {
    type ToolParameter,
    type ToolTestResponse,
    type ToolValidationMessage,
    type NewToolDefinition,
    testTool,
    validateTool,
} from "../config/api";
import { EditLabelsModal } from "./EditLabelsModal";
import { LabelDisplay } from "./LabelDisplay";
import { ValidationProblemsPanel } from "./ValidationProblemsPanel";

/** A validation problem shown in the Problems tab. */
export type ValidationMessage = ToolValidationMessage;

/** Props of {@link ToolEditor}. */
export interface ToolEditorProps {
    /** The tool as configuration-item JSON: name, description, parameters, scriptTemplate, labels. */
    value: NewToolDefinition;
    /** Called with the complete new value on every edit. */
    onChange: (value: NewToolDefinition) => void;
    /** Disables every input and hides AI helpers and label editing. */
    readOnly?: boolean;
    /** Problems to show in the Problems tab instead of running the editor's own validation. */
    problems?: ValidationMessage[];
    /** Called with the messages of the editor's own validation (only when `problems` is not given). */
    onValidation?: (messages: ValidationMessage[]) => void;
    /** Saved tool id; the Test tab is shown only when set (testing drafts is Phase 5). */
    toolId?: number;
    /** Workbench only: adds "Ask AI to fix this" to each listed problem. */
    onAskAiAboutProblem?: (message: ToolValidationMessage) => void;
    /** Disables the "Ask AI to fix this" actions. */
    askAiDisabled?: boolean;
}

/** Style that makes a fieldset a transparent wrapper (used to disable tab bodies when read-only). */
const READ_ONLY_FIELDSET_STYLE = { border: 0, padding: 0, margin: 0, minWidth: 0 };

/**
 * Controlled editor for a tool: Info, Parameters, Script Template, Test and Problems tabs, the AI
 * assistant and label editing. The value is the single source of truth; every edit emits the
 * complete new value.
 */
export function ToolEditor({ value, onChange, readOnly = false, problems, onValidation, toolId, onAskAiAboutProblem, askAiDisabled }: ToolEditorProps) {
    const form = value;
    const params = value.parameters || [];
    const labels = value.labels || [];
    const [activeTab, setActiveTab] = useState(0);
    const [isLabelsOpen, setIsLabelsOpen] = useState(false);
    const [ownMessages, setOwnMessages] = useState<ValidationMessage[]>([]);
    const onValidationRef = useRef(onValidation);
    onValidationRef.current = onValidation;
    const validationMessages = problems ?? ownMessages;
    const selfValidate = problems === undefined;

    // Validate the current value on every change (not debounced, as before the extraction).
    useEffect(() => {
        if (!selfValidate) return;
        let cancelled = false;
        validateTool({ ...value, parameters: value.parameters || [] })
            .then((result) => {
                if (cancelled) return;
                const messages = result.messages || [];
                setOwnMessages(messages);
                onValidationRef.current?.(messages);
            })
            .catch(console.error);
        return () => { cancelled = true; };
    }, [value, selfValidate]);

    // Fall back to Info when the active tab (Test or Problems) is no longer rendered.
    useEffect(() => {
        if ((activeTab === 3 && toolId === undefined) || (activeTab === 4 && validationMessages.length === 0)) {
            setActiveTab(0);
        }
    }, [activeTab, toolId, validationMessages.length]);

    const updateForm = (updates: Partial<NewToolDefinition>) => {
        if (readOnly) return;
        onChange({ ...form, parameters: params, labels, ...updates });
    };

    const addParam = () => {
        updateForm({ parameters: [...params, { name: "", type: "string", description: "", required: true }] });
    };

    const updateParam = (index: number, updates: Partial<ToolParameter>) => {
        updateForm({ parameters: params.map((p, i) => i === index ? { ...p, ...updates } : p) });
    };

    const removeParam = (index: number) => {
        updateForm({ parameters: params.filter((_, i) => i !== index) });
    };

    return (
        <>
            <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                <Tab eventKey={0} title={<TabTitleText>Info</TabTitleText>}>
                    <TabContent id="info-tab" eventKey={0} activeKey={activeTab} style={{ marginTop: "24px" }}>
                        <fieldset disabled={readOnly} style={READ_ONLY_FIELDSET_STYLE}>
                            <InfoTab form={form} updateForm={updateForm} readOnly={readOnly}
                                labels={labels}
                                onEditLabels={readOnly ? undefined : () => setIsLabelsOpen(true)} />
                        </fieldset>
                    </TabContent>
                </Tab>
                <Tab eventKey={1} title={<TabTitleText>Parameters ({params.length})</TabTitleText>}>
                    <TabContent id="params-tab" eventKey={1} activeKey={activeTab} style={{ marginTop: "24px" }}>
                        <fieldset disabled={readOnly} style={READ_ONLY_FIELDSET_STYLE}>
                            <ParametersTab
                                params={params}
                                readOnly={readOnly}
                                addParam={addParam}
                                updateParam={updateParam}
                                removeParam={removeParam}
                            />
                        </fieldset>
                    </TabContent>
                </Tab>
                <Tab eventKey={2} title={<TabTitleText>Script Template</TabTitleText>}>
                    <TabContent id="script-tab" eventKey={2} activeKey={activeTab} style={{ marginTop: "24px" }}>
                        <ScriptTemplateTab
                            value={form.scriptTemplate || ""}
                            readOnly={readOnly}
                            onChange={(v) => updateForm({ scriptTemplate: v })}
                        />
                    </TabContent>
                </Tab>
                {toolId !== undefined && (
                    <Tab eventKey={3} title={<TabTitleText>Test</TabTitleText>}>
                        <TabContent id="test-tab" eventKey={3} activeKey={activeTab} style={{ marginTop: "24px" }}>
                            <TestTab toolId={toolId} params={params} scriptTemplate={form.scriptTemplate} />
                        </TabContent>
                    </Tab>
                )}
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
                            <ValidationProblemsPanel messages={validationMessages} onAskAi={onAskAiAboutProblem}
                                askAiDisabled={askAiDisabled} />
                        </TabContent>
                    </Tab>
                )}
            </Tabs>

            <EditLabelsModal
                isOpen={!readOnly && isLabelsOpen}
                labels={labels}
                onSave={(newLabels) => updateForm({ labels: newLabels })}
                onClose={() => setIsLabelsOpen(false)}
            />
        </>
    );
}

function InfoTab({ form, updateForm, labels, onEditLabels, readOnly }: {
    form: NewToolDefinition;
    updateForm: (updates: Partial<NewToolDefinition>) => void;
    labels: string[];
    onEditLabels?: () => void;
    readOnly: boolean;
}) {
    return (
        <Form style={{ maxWidth: "600px" }}>
            <FormGroup label="Name" isRequired fieldId="name">
                <TextInput id="name" isRequired value={form.name} isDisabled={readOnly}
                    onChange={(_e, v) => updateForm({ name: v })} />
            </FormGroup>

            <FormGroup label="Description" fieldId="description">
                <TextArea id="description" value={form.description || ""} isDisabled={readOnly}
                    onChange={(_e, v) => updateForm({ description: v })} rows={3} />
            </FormGroup>

            <FormGroup label="Labels" fieldId="labels">
                <LabelDisplay labels={labels} onEdit={onEditLabels} />
            </FormGroup>
        </Form>
    );
}

function ParametersTab({ params, addParam, updateParam, removeParam, readOnly }: {
    params: ToolParameter[];
    readOnly: boolean;
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
                                        isDisabled={readOnly}
                                        onChange={(_e, v) => updateParam(i, { name: v })}
                                        placeholder="param_name" />
                                </Td>
                                <Td>
                                    <FormSelect id={`param-type-${i}`}
                                        aria-label={`Parameter ${i} type`}
                                        value={p.type}
                                        isDisabled={readOnly}
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
                                        isDisabled={readOnly}
                                        onChange={(_e, v) => updateParam(i, { description: v })}
                                        placeholder="Description" />
                                </Td>
                                <Td>
                                    <input type="checkbox" checked={p.required || false} disabled={readOnly}
                                        onChange={(e) => updateParam(i, { required: e.target.checked })} />
                                </Td>
                                <Td>
                                    <Button variant="plain" size="sm" isDisabled={readOnly} onClick={() => removeParam(i)}>
                                        <TimesIcon />
                                    </Button>
                                </Td>
                            </Tr>
                        ))}
                    </Tbody>
                </Table>
            )}

            <Button variant="link" icon={<PlusCircleIcon />} onClick={addParam} isDisabled={readOnly}
                style={{ marginTop: "8px" }}>
                Add Parameter
            </Button>
        </div>
    );
}

function ScriptTemplateTab({ value, onChange, readOnly }: {
    value: string;
    readOnly: boolean;
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
                isReadOnly={readOnly}
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
    const [envVars, setEnvVars] = useState<{key: string, value: string}[]>([]);
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

        const environment: Record<string, string> = {};
        for (const ev of envVars) {
            if (ev.key.trim()) {
                environment[ev.key.trim()] = ev.value;
            }
        }

        testTool(toolId, {
            scriptTemplate,
            parameters: paramValues,
            environment: Object.keys(environment).length > 0 ? environment : undefined,
        })
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
                Test this tool by providing parameter values and optional environment variables.
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

            <Title headingLevel="h4" size="md" style={{ marginTop: "24px", marginBottom: "8px" }}>
                Environment Variables
            </Title>
            <HelperText style={{ marginBottom: "12px" }}>
                <HelperTextItem>
                    Provide environment variables for the test. Use <code>{"${secret:NAME}"}</code> to
                    reference secrets from the Axiom secrets store.
                </HelperTextItem>
            </HelperText>

            {envVars.map((ev, i) => (
                <Flex key={i} style={{ marginBottom: "8px", gap: "8px" }}
                    alignItems={{ default: "alignItemsFlexStart" }}>
                    <FlexItem style={{ flex: 1 }}>
                        <TextInput
                            aria-label="Variable name"
                            placeholder="Variable name (e.g. GH_TOKEN)"
                            value={ev.key}
                            onChange={(_e, v) => {
                                const updated = [...envVars];
                                updated[i] = { ...ev, key: v };
                                setEnvVars(updated);
                            }}
                        />
                    </FlexItem>
                    <FlexItem style={{ flex: 2 }}>
                        <TextInput
                            aria-label="Variable value"
                            placeholder="Value or ${secret:SECRET_NAME}"
                            value={ev.value}
                            onChange={(_e, v) => {
                                const updated = [...envVars];
                                updated[i] = { ...ev, value: v };
                                setEnvVars(updated);
                            }}
                        />
                    </FlexItem>
                    <FlexItem>
                        <Button variant="plain" aria-label="Remove variable"
                            onClick={() => setEnvVars(envVars.filter((_, idx) => idx !== i))}>
                            <TrashIcon />
                        </Button>
                    </FlexItem>
                </Flex>
            ))}
            <Button variant="link" icon={<PlusCircleIcon />}
                onClick={() => setEnvVars([...envVars, { key: "", value: "" }])}
                style={{ marginBottom: "16px" }}>
                Add Environment Variable
            </Button>

            <br />

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
