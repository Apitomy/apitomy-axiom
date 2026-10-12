import { useState, useEffect, useRef, useMemo } from "react";
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
    Modal,
    ModalBody,
    ModalHeader,
    Tab,
    TabContent,
    TabTitleText,
    Tabs,
    TextArea,
    TextInput,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import HelpIcon from "@patternfly/react-icons/dist/esm/icons/help-icon";
import MagicIcon from "@patternfly/react-icons/dist/esm/icons/magic-icon";
import ExclamationTriangleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-triangle-icon";
import ExclamationCircleIcon from "@patternfly/react-icons/dist/esm/icons/exclamation-circle-icon";
import { CodeEditor, Language } from "@patternfly/react-code-editor";
import { registerPlaceholderCompletions, inputPlaceholderItems, SCRIPT_ENV_VARS } from "./PlaceholderCompletionProvider";
import { EditLabelsModal } from "./EditLabelsModal";
import { AiConfigTab } from "./AiConfigTab";
import { EnvironmentTab } from "./EnvironmentTab";
import { ActionTypeFieldsTab } from "./ActionTypeFieldsTab";
import { LabelDisplay } from "./LabelDisplay";
import { ToolListEditor } from "./ToolListEditor";
import { NodeCodeTab } from "./NodeCodeTab";
import { ScriptAiModal } from "./ScriptAiModal";
import { ActionTypeAiModal } from "./ActionTypeAiModal";
import { ValidationProblemsPanel } from "./ValidationProblemsPanel";
import {
    type NewActionType,
    type ToolValidationMessage,
    validateActionType,
    fetchModels,
    fetchEngines,
    type ActionTypeField,
} from "../config/api";

/** A validation problem shown in the Problems tab. */
export type ValidationMessage = ToolValidationMessage;

/** Props of {@link ActionTypeEditor}. */
export interface ActionTypeEditorProps {
    /** The action type as configuration-item JSON (NewActionType shape; allowedTools and environment included). */
    value: NewActionType;
    /** Called with the complete new value on every edit. */
    onChange: (value: NewActionType) => void;
    /** Disables every input and hides AI helpers and label editing. */
    readOnly?: boolean;
    /** Problems to show in the Problems tab instead of running the editor's own validation. */
    problems?: ValidationMessage[];
    /** Called with the messages of the editor's own validation (only when `problems` is not given). */
    onValidation?: (messages: ValidationMessage[]) => void;
}

/** Style that makes a fieldset a transparent wrapper (used to disable tab bodies when read-only). */
const READ_ONLY_FIELDSET_STYLE = { border: 0, padding: 0, margin: 0, minWidth: 0 };

/**
 * Builds the payload sent to validation: allowedTools always present, empty environment omitted.
 */
function toValidationData(value: NewActionType): NewActionType {
    const env = value.environment || {};
    return {
        ...value,
        allowedTools: value.allowedTools || [],
        environment: Object.keys(env).length > 0 ? env : undefined,
    };
}

/**
 * Controlled editor for an action type: all tabs, AI helpers, label editing and validation.
 * The value is the single source of truth; every edit emits the complete new value.
 */
export function ActionTypeEditor({ value, onChange, readOnly = false, problems, onValidation }: ActionTypeEditorProps) {
    const form = value;
    const tools = value.allowedTools || [];
    const envVars = value.environment || {};
    const [activeTab, setActiveTab] = useState(0);
    const [aiModalOpen, setAiModalOpen] = useState(false);
    const [isLabelsOpen, setIsLabelsOpen] = useState(false);
    const [availableModels, setAvailableModels] = useState<string[]>([]);
    const [availableEngines, setAvailableEngines] = useState<string[]>([]);
    const [ownMessages, setOwnMessages] = useState<ValidationMessage[]>([]);
    const validationTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    const onValidationRef = useRef(onValidation);
    onValidationRef.current = onValidation;
    const validationMessages = problems ?? ownMessages;
    const selfValidate = problems === undefined;

    // Debounced validation of the current value (skipped when problems are supplied).
    useEffect(() => {
        if (!selfValidate) return;
        if (validationTimerRef.current) {
            clearTimeout(validationTimerRef.current);
        }
        validationTimerRef.current = setTimeout(() => {
            validateActionType(toValidationData(value))
                .then((result) => {
                    setOwnMessages(result.messages);
                    onValidationRef.current?.(result.messages);
                })
                .catch(console.error);
        }, 500);
        return () => {
            if (validationTimerRef.current) clearTimeout(validationTimerRef.current);
        };
    }, [value, selfValidate]);

    useEffect(() => {
        fetchEngines().then(setAvailableEngines).catch(console.error);
    }, []);

    useEffect(() => {
        fetchModels(form.engine || undefined).then(setAvailableModels).catch(console.error);
    }, [form.engine]);

    // Keep the active tab valid: conditional tabs (Inputs/Outputs, agent/script,
    // Problems) unmount when their condition turns off. If the active tab is no
    // longer rendered — e.g. unchecking "Workflow enabled" while on Inputs/Outputs —
    // fall back to the Info tab so the panel does not go blank.
    useEffect(() => {
        const visible = new Set<number>([0, 2]);
        if (form.executionMode === "agent") {
            visible.add(1);
            visible.add(3);
            visible.add(10);
        }
        if (form.executionMode === "script") {
            visible.add(4);
        }
        if (form.executionMode === "node") {
            visible.add(11);
        }
        visible.add(6);
        visible.add(7);
        if (validationMessages.length > 0) {
            visible.add(5);
        }
        if (!visible.has(activeTab)) {
            setActiveTab(0);
        }
    }, [form.executionMode, validationMessages.length, activeTab]);

    const updateForm = (updates: Partial<NewActionType>) => {
        if (readOnly) return;
        if (updates.engine !== undefined && updates.engine !== form.engine) {
            updates = { ...updates, model: undefined };
        }
        onChange({ ...form, ...updates });
    };

    const addTool = (tool: string) => {
        if (tool && !tools.includes(tool)) {
            updateForm({ allowedTools: [...tools, tool] });
        }
    };

    const removeTool = (tool: string) => {
        updateForm({ allowedTools: tools.filter((t) => t !== tool) });
    };

    const replaceTools = (newTools: string[]) => {
        updateForm({ allowedTools: newTools });
    };

    return (
        <>
            {!readOnly && (form.executionMode === "agent" || form.executionMode === "script") && (
                <Flex justifyContent={{ default: "justifyContentFlexEnd" }} style={{ marginBottom: "8px" }}>
                    <FlexItem>
                        <Button variant="secondary" icon={<MagicIcon />}
                            onClick={() => setAiModalOpen(true)}>
                            AI Assistant
                        </Button>
                    </FlexItem>
                </Flex>
            )}
            <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                <Tab eventKey={0} title={<TabTitleText>Info</TabTitleText>}>
                    <TabContent id="info-tab" eventKey={0} activeKey={activeTab} style={{ marginTop: "24px" }}>
                        <fieldset disabled={readOnly} style={READ_ONLY_FIELDSET_STYLE}>
                            <InfoTab form={form} readOnly={readOnly} updateForm={updateForm} onEditLabels={() => setIsLabelsOpen(true)} />
                        </fieldset>
                    </TabContent>
                </Tab>
                {form.executionMode === "agent" && (
                    <Tab eventKey={1} title={<TabTitleText>Allowed Tools ({tools.length})</TabTitleText>}>
                        <TabContent id="tools-tab" eventKey={1} activeKey={activeTab} style={{ marginTop: "24px" }}>
                            <fieldset disabled={readOnly} style={READ_ONLY_FIELDSET_STYLE}>
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
                                                No tools configured. The agent will use minimal read-only defaults.
                                            </EmptyStateBody>
                                        </EmptyState>
                                    }
                                />
                            </fieldset>
                        </TabContent>
                    </Tab>
                )}
                <Tab eventKey={2} title={<TabTitleText>
                    Environment{Object.keys(envVars).length > 0 ? ` (${Object.keys(envVars).length})` : ""}
                </TabTitleText>}>
                    <TabContent id="env-tab" eventKey={2} activeKey={activeTab} style={{ marginTop: "24px" }}>
                        <fieldset disabled={readOnly} style={READ_ONLY_FIELDSET_STYLE}>
                            <EnvironmentTab
                                envVars={envVars}
                                onChange={(updated) => updateForm({ environment: updated })}
                            />
                        </fieldset>
                    </TabContent>
                </Tab>
                <Tab eventKey={6} title={<TabTitleText>Inputs ({(form.inputs || []).length})</TabTitleText>}>
                    <TabContent id="inputs-tab" eventKey={6} activeKey={activeTab} style={{ marginTop: "24px" }}>
                        <fieldset disabled={readOnly} style={READ_ONLY_FIELDSET_STYLE}>
                            <ActionTypeFieldsTab
                                kind="input"
                                fields={form.inputs || []}
                                onChange={(updated) => updateForm({ inputs: updated })}
                            />
                        </fieldset>
                    </TabContent>
                </Tab>
                <Tab eventKey={7} title={<TabTitleText>Outputs ({(form.outputs || []).length})</TabTitleText>}>
                    <TabContent id="outputs-tab" eventKey={7} activeKey={activeTab} style={{ marginTop: "24px" }}>
                        <fieldset disabled={readOnly} style={READ_ONLY_FIELDSET_STYLE}>
                            <ActionTypeFieldsTab
                                kind="output"
                                fields={form.outputs || []}
                                onChange={(updated) => updateForm({ outputs: updated })}
                            />
                        </fieldset>
                    </TabContent>
                </Tab>
                {form.executionMode === "agent" && (
                    <Tab eventKey={3} title={<TabTitleText>Prompt Template</TabTitleText>}>
                        <TabContent id="prompt-tab" eventKey={3} activeKey={activeTab} style={{ marginTop: "24px" }}>
                            <fieldset disabled={readOnly} style={READ_ONLY_FIELDSET_STYLE}>
                                <PromptTemplateTab
                                    value={form.promptTemplate || ""}
                                    onChange={(v) => updateForm({ promptTemplate: v })}
                                    inputs={form.inputs || []}
                                />
                            </fieldset>
                        </TabContent>
                    </Tab>
                )}
                {form.executionMode === "agent" && (
                    <Tab eventKey={10} title={<TabTitleText>AI Config</TabTitleText>}>
                        <TabContent id="ai-config-tab" eventKey={10} activeKey={activeTab} style={{ marginTop: "24px" }}>
                            <fieldset disabled={readOnly} style={READ_ONLY_FIELDSET_STYLE}>
                                <Form style={{ maxWidth: "600px" }}>
                                    <AiConfigTab
                                        values={form}
                                        onChange={updateForm}
                                        availableEngines={availableEngines}
                                        availableModels={availableModels}
                                    />
                                </Form>
                            </fieldset>
                        </TabContent>
                    </Tab>
                )}
                {form.executionMode === "script" && (
                    <Tab eventKey={4} title={<TabTitleText>Script</TabTitleText>}>
                        <TabContent id="script-tab" eventKey={4} activeKey={activeTab} style={{ marginTop: "24px" }}>
                            <fieldset disabled={readOnly} style={READ_ONLY_FIELDSET_STYLE}>
                                <ScriptTab
                                    value={form.scriptTemplate || ""}
                                    onChange={(v) => updateForm({ scriptTemplate: v })}
                                    inputs={form.inputs || []}
                                />
                            </fieldset>
                        </TabContent>
                    </Tab>
                )}
                {form.executionMode === "node" && (
                    <Tab eventKey={11} title={<TabTitleText>Code</TabTitleText>}>
                        <TabContent id="code-tab" eventKey={11} activeKey={activeTab} style={{ marginTop: "24px" }}>
                            <fieldset disabled={readOnly} style={READ_ONLY_FIELDSET_STYLE}>
                                <NodeCodeTab
                                    value={form.codeTemplate || ""}
                                    onChange={(v) => updateForm({ codeTemplate: v })}
                                />
                            </fieldset>
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

            {!readOnly && form.executionMode === "script" && (
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
            {!readOnly && form.executionMode === "agent" && (
                <ActionTypeAiModal
                    isOpen={aiModalOpen}
                    promptTemplate={form.promptTemplate || ""}
                    allowedTools={tools}
                    actionTypeName={form.name}
                    actionTypeDescription={form.description}
                    onApply={(prompt, newTools) => {
                        updateForm({ promptTemplate: prompt, allowedTools: newTools });
                    }}
                    onClose={() => setAiModalOpen(false)}
                />
            )}

            <EditLabelsModal
                isOpen={!readOnly && isLabelsOpen}
                labels={form.labels || []}
                onSave={async (labels) => {
                    updateForm({ labels });
                }}
                onClose={() => setIsLabelsOpen(false)}
            />
        </>
    );
}

function InfoTab({ form, readOnly, updateForm, onEditLabels }: {
    form: NewActionType;
    readOnly: boolean;
    updateForm: (updates: Partial<NewActionType>) => void;
    onEditLabels: () => void;
}) {
    return (
        <Form style={{ maxWidth: "600px" }}>
            <FormGroup label="Name" isRequired fieldId="name">
                <TextInput
                    id="name"
                    isRequired
                    value={form.name}
                    isDisabled={readOnly}
                    onChange={(_e, v) => updateForm({ name: v })}
                />
            </FormGroup>
            <FormGroup label="Description" fieldId="description">
                <TextArea
                    id="description"
                    value={form.description || ""}
                    isDisabled={readOnly}
                    onChange={(_e, v) => updateForm({ description: v })}
                    rows={3}
                />
            </FormGroup>
            <FormGroup label="Labels" fieldId="labels">
                <LabelDisplay labels={form.labels || []} onEdit={onEditLabels} />
            </FormGroup>
            <FormGroup label="Execution Mode" isRequired fieldId="executionMode">
                <FormSelect
                    id="executionMode"
                    value={form.executionMode}
                    isDisabled={readOnly}
                    onChange={(_e, v) => updateForm({ executionMode: v })}
                >
                    <FormSelectOption value="agent" label="Agent — executed by an AI agent" />
                    <FormSelectOption value="script" label="Script — executes a bash script" />
                    <FormSelectOption value="node" label="Node.js — runs a JavaScript module" />
                </FormSelect>
            </FormGroup>
        </Form>
    );
}

function PromptTemplateTab({ value, onChange, inputs }: {
    value: string;
    onChange: (v: string) => void;
    inputs: ActionTypeField[];
}) {
    const effectiveTheme = useEffectiveTheme();
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const editorRef = useRef<any>(null);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const monacoRef = useRef<any>(null);

    // Build dynamic placeholders from defined inputs
    const allPlaceholders = useMemo(() => {
        return inputPlaceholderItems(inputs);
    }, [inputs]);

    // Re-register completions when inputs change
    useEffect(() => {
        if (editorRef.current && monacoRef.current) {
            registerPlaceholderCompletions(editorRef.current, monacoRef.current, "markdown", allPlaceholders);
        }
    }, [allPlaceholders]);

    const [helpOpen, setHelpOpen] = useState(false);

    const insertPlaceholder = (name: string) => {
        const text = `{{${name}}}`;
        const editor = editorRef.current;
        if (editor) {
            const position = editor.getPosition();
            if (position) {
                editor.executeEdits("placeholder-insert", [{
                    range: {
                        startLineNumber: position.lineNumber,
                        startColumn: position.column,
                        endLineNumber: position.lineNumber,
                        endColumn: position.column,
                    },
                    text,
                }]);
                editor.focus();
            }
        } else {
            onChange(value + text);
        }
        setHelpOpen(false);
    };

    return (
        <div>
            <Flex alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "12px", gap: "8px" }}>
                <FlexItem>
                    <span className="axiom-text-subtle">
                        The prompt sent to the AI agent when executing this action type.
                        Placeholders are substituted at runtime.
                    </span>
                </FlexItem>
                <FlexItem>
                    <Button variant="plain" aria-label="Placeholder reference"
                        onClick={() => setHelpOpen(true)}>
                        <HelpIcon />
                    </Button>
                </FlexItem>
            </Flex>
            <CodeEditor
                code={value}
                onCodeChange={(v) => onChange(v)}
                language={Language.markdown}
                height="500px"
                isDarkTheme={effectiveTheme === "dark"}
                isLineNumbersVisible
                onEditorDidMount={(editor, monaco) => {
                    editorRef.current = editor;
                    monacoRef.current = monaco;
                    registerPlaceholderCompletions(editor, monaco, "markdown", allPlaceholders);
                }}
            />

            <Modal isOpen={helpOpen} onClose={() => setHelpOpen(false)} variant="large"
                aria-label="Prompt template placeholder reference">
                <ModalHeader title="Prompt Template Placeholders" />
                <ModalBody>
                    <p style={{ marginBottom: "16px" }}>
                        The only placeholder is {"{{inputs.<name>}}"} for a declared input. Axiom
                        also appends an Inputs section with every valued input and the output
                        contract. Click a placeholder to insert it into the prompt template.
                    </p>
                    <Table aria-label="Template placeholders" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Placeholder</Th>
                                <Th>Description</Th>
                            </Tr>
                        </Thead>
                        <Tbody>
                            {allPlaceholders.map((p) => (
                                <Tr key={p.name} isClickable
                                    onRowClick={() => insertPlaceholder(p.name)}>
                                    <Td>
                                        <code style={{ cursor: "pointer", color: "var(--pf-v6-global--link--Color, #0066cc)" }}>
                                            {`{{${p.name}}}`}
                                        </code>
                                    </Td>
                                    <Td>{p.description}</Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                </ModalBody>
            </Modal>
        </div>
    );
}

function ScriptTab({ value, onChange, inputs }: {
    value: string;
    onChange: (v: string) => void;
    inputs: ActionTypeField[];
}) {
    const effectiveTheme = useEffectiveTheme();
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const editorRef = useRef<any>(null);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const monacoRef = useRef<any>(null);
    const [helpOpen, setHelpOpen] = useState(false);

    const allPlaceholders = useMemo(() => {
        return inputPlaceholderItems(inputs);
    }, [inputs]);

    useEffect(() => {
        if (editorRef.current && monacoRef.current) {
            registerPlaceholderCompletions(editorRef.current, monacoRef.current, "shell", allPlaceholders);
        }
    }, [allPlaceholders]);

    const insertPlaceholder = (name: string) => {
        const text = `{{${name}}}`;
        const editor = editorRef.current;
        if (editor) {
            const position = editor.getPosition();
            if (position) {
                editor.executeEdits("placeholder-insert", [{
                    range: {
                        startLineNumber: position.lineNumber,
                        startColumn: position.column,
                        endLineNumber: position.lineNumber,
                        endColumn: position.column,
                    },
                    text,
                }]);
                editor.focus();
            }
        } else {
            onChange(value + text);
        }
        setHelpOpen(false);
    };

    return (
        <div>
            <Flex alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "12px", gap: "8px" }}>
                <FlexItem>
                    <span className="axiom-text-subtle">
                        A bash script that runs when this action type is triggered.
                        Placeholders are substituted at runtime.
                    </span>
                </FlexItem>
                <FlexItem>
                    <Button variant="plain" aria-label="Placeholder reference"
                        onClick={() => setHelpOpen(true)}>
                        <HelpIcon />
                    </Button>
                </FlexItem>
            </Flex>
            <CodeEditor
                code={value}
                onCodeChange={(v) => onChange(v)}
                language={Language.shell}
                height="500px"
                isDarkTheme={effectiveTheme === "dark"}
                isLineNumbersVisible
                onEditorDidMount={(editor, monaco) => {
                    editorRef.current = editor;
                    monacoRef.current = monaco;
                    registerPlaceholderCompletions(editor, monaco, "shell", allPlaceholders);
                }}
            />

            <Modal isOpen={helpOpen} onClose={() => setHelpOpen(false)} variant="large"
                aria-label="Script template placeholder reference">
                <ModalHeader title="Script Template Placeholders" />
                <ModalBody>
                    <p style={{ marginBottom: "16px" }}>
                        The only placeholder is {"{{inputs.<name>}}"} for a declared input. Click a
                        placeholder to insert it into the script template.
                    </p>
                    <Table aria-label="Script placeholders" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Placeholder</Th>
                                <Th>Description</Th>
                            </Tr>
                        </Thead>
                        <Tbody>
                            {allPlaceholders.map((p) => (
                                <Tr key={p.name} isClickable
                                    onRowClick={() => insertPlaceholder(p.name)}>
                                    <Td>
                                        <code style={{ cursor: "pointer", color: "var(--pf-v6-global--link--Color, #0066cc)" }}>
                                            {`{{${p.name}}}`}
                                        </code>
                                    </Td>
                                    <Td>{p.description}</Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                    <p style={{ margin: "16px 0" }}>
                        These environment variables are always available to the script:
                    </p>
                    <Table aria-label="Script environment variables" variant="compact">
                        <Thead>
                            <Tr>
                                <Th>Variable</Th>
                                <Th>Description</Th>
                            </Tr>
                        </Thead>
                        <Tbody>
                            {SCRIPT_ENV_VARS.map((v) => (
                                <Tr key={v.name}>
                                    <Td><code>{`$${v.name}`}</code></Td>
                                    <Td>{v.description}</Td>
                                </Tr>
                            ))}
                        </Tbody>
                    </Table>
                </ModalBody>
            </Modal>
        </div>
    );
}

