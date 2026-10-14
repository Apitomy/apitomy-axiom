import { useState } from "react";
import {
    Alert, Button, Form, FormGroup, Label, Modal, ModalBody, ModalFooter, ModalHeader, Tab, TabContentBody, Tabs, TabTitleText, TextArea, TextInput, Title, ToggleGroup,
    ToggleGroupItem,
} from "@patternfly/react-core";
import { Light as SyntaxHighlighter } from "react-syntax-highlighter";
import bash from "react-syntax-highlighter/dist/esm/languages/hljs/bash";
import javascript from "react-syntax-highlighter/dist/esm/languages/hljs/javascript";
import json from "react-syntax-highlighter/dist/esm/languages/hljs/json";
import { stackoverflowDark, stackoverflowLight } from "react-syntax-highlighter/dist/esm/styles/hljs";
import CheckIcon from "@patternfly/react-icons/dist/esm/icons/check-icon";
import CopyIcon from "@patternfly/react-icons/dist/esm/icons/copy-icon";
import type { CompositionTestRun } from "../../config/compositions";
import { useEffectiveTheme } from "../../hooks/useTheme";

SyntaxHighlighter.registerLanguage("json", json);
SyntaxHighlighter.registerLanguage("bash", bash);
SyntaxHighlighter.registerLanguage("javascript", javascript);

/** Languages a code block can be highlighted as; "plaintext" is shown without highlighting. */
export type CodeLanguage = "json" | "bash" | "javascript" | "plaintext";

/**
 * Pretty-prints text when it is a JSON object or array.
 * @param text the text
 * @returns the pretty-printed JSON, or null when the text is not a JSON object or array
 */
export function prettyJson(text: string | undefined): string | null {
    const trimmed: string = (text ?? "").trim();
    if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
        return null;
    }
    try {
        return JSON.stringify(JSON.parse(trimmed), null, 2);
    } catch {
        return null;
    }
}

/**
 * Read-only code block highlighted with SyntaxHighlighter (the same highlighter as the chat), with a copy
 * button. Long content scrolls inside the block.
 */
export function CodeBlock({ code, language, label }: { code: string; language: CodeLanguage; label: string }) {
    const theme = useEffectiveTheme();
    const [copied, setCopied] = useState<boolean>(false);
    const copy = () => {
        void navigator.clipboard?.writeText(code).then(() => {
            setCopied(true);
            setTimeout(() => setCopied(false), 1500);
        });
    };
    return (
        <div aria-label={label} className="axiom-test-run__code" data-language={language}>
            <Button variant="plain" size="sm" className="axiom-test-run__copy" aria-label={`Copy ${label}`}
                icon={copied ? <CheckIcon /> : <CopyIcon />} onClick={copy} />
            {language === "plaintext"
                ? <pre className="axiom-test-run__plain">{code}</pre>
                : (
                    <SyntaxHighlighter language={language} style={theme === "dark" ? stackoverflowDark : stackoverflowLight}
                        wrapLongLines customStyle={{ margin: 0, padding: "8px 40px 8px 12px", fontSize: "13px" }}>
                        {code}
                    </SyntaxHighlighter>
                )}
        </div>
    );
}

/** A titled stream (stdout or stderr): highlighted as JSON when it is JSON, otherwise plain text. */
function StreamSection({ title, text }: { title: string; text: string | undefined }) {
    const json: string | null = prettyJson(text);
    return (
        <section className="axiom-test-run__section">
            <Title headingLevel="h3" size="md">{title}</Title>
            {text ? <CodeBlock code={json ?? text} language={json ? "json" : "plaintext"} label={title} />
                : <p className="axiom-text-subtle">(empty)</p>}
        </section>
    );
}

/** One value of a field list: scalars inline, objects and arrays as highlighted JSON. */
function FieldValue({ id, name, value }: { id: string; name: string; value: unknown }) {
    if (value !== null && typeof value === "object") {
        return <CodeBlock code={JSON.stringify(value, null, 2)} language="json" label={name} />;
    }
    const text: string = typeof value === "string" ? value : String(value);
    if (text.includes("\n")) {
        return <TextArea id={id} value={text} readOnlyVariant="default" aria-label={name} autoResize
            resizeOrientation="vertical" />;
    }
    return <TextInput id={id} value={text} readOnlyVariant="default" aria-label={name}
        placeholder={value === "" ? "(empty)" : undefined} />;
}

/** How the Inputs/Parameters/Outputs tabs show their values. */
export type ValuesView = "list" | "json";

/**
 * A set of named values (inputs, parameters or outputs) as a list of fields, or as one JSON object. The
 * header has the list/JSON switch and a button that copies the whole set as JSON.
 */
function ValuesPanel({ label, values, view, onViewChange, emptyText }: {
    label: string;
    values: Record<string, unknown>;
    view: ValuesView;
    onViewChange(view: ValuesView): void;
    emptyText: string;
}) {
    const entries: [string, unknown][] = Object.entries(values);
    const json: string = JSON.stringify(values, null, 2);
    const [copied, setCopied] = useState<boolean>(false);
    if (entries.length === 0) {
        return <p className="axiom-text-subtle">{emptyText}</p>;
    }
    return (
        <>
            <div className="axiom-test-run__values-toolbar">
                <ToggleGroup isCompact aria-label={`Show ${label.toLowerCase()} as`}>
                    <ToggleGroupItem text="List" buttonId={`${label}-list`} isSelected={view === "list"}
                        onChange={() => onViewChange("list")} />
                    <ToggleGroupItem text="JSON" buttonId={`${label}-json`} isSelected={view === "json"}
                        onChange={() => onViewChange("json")} />
                </ToggleGroup>
                <Button variant="link" size="sm" icon={copied ? <CheckIcon /> : <CopyIcon />}
                    onClick={() => void navigator.clipboard?.writeText(json).then(() => {
                        setCopied(true);
                        setTimeout(() => setCopied(false), 1500);
                    })}>Copy as JSON</Button>
            </div>
            {view === "json" ? <CodeBlock code={json} language="json" label={label} /> : (
                <Form aria-label={label} className="axiom-test-run__fields" onSubmit={(e) => e.preventDefault()}>
                    {entries.map(([name, value]) => {
                        const id: string = `test-run-${label}-${name}`.toLowerCase();
                        return (
                            <FormGroup key={name} label={name} fieldId={id}>
                                <FieldValue id={id} name={name} value={value} />
                            </FormGroup>
                        );
                    })}
                </Form>
            )}
        </>
    );
}

/** Tabs of the test run dialog. */
type RunTab = "source" | "inputs" | "outputs" | "streams";

/**
 * The source tab of a run: "Script" for script action types and tools, "Code" for node action types, none
 * otherwise (agent action types, or a run that never got to run).
 */
export function sourceTab(run: CompositionTestRun): { title: string; language: "bash" | "javascript" } | null {
    if (!run.resolvedScript) {
        return null;
    }
    return run.executionMode === "node" ? { title: "Code", language: "javascript" } : { title: "Script", language: "bash" };
}


/** Props for {@link TestRunDetailModal}. */
export interface TestRunDetailModalProps {
    run: CompositionTestRun;
    onClose(): void;
    /** Opens the run's item in a tab. */
    onOpenItem(itemId: number): void;
    /** Runs an AI-suggested test (shown for suggested runs only). */
    onRunSuggestion?(run: CompositionTestRun): void;
}

const STATUS_COLOR: Record<CompositionTestRun["status"], "blue" | "green" | "red" | "orange" | "purple"> = {
    running: "blue", passed: "green", failed: "red", error: "orange", suggested: "purple",
};

/**
 * Shows one test run: its status and timing, inputs and outputs (JSON highlighted), problems, stdout,
 * stderr and the resolved script.
 */
export function TestRunDetailModal({ run, onClose, onOpenItem, onRunSuggestion }: TestRunDetailModalProps) {
    const source = sourceTab(run);
    // Always open on the first tab: Script/Code when the run has one, otherwise Inputs/Parameters.
    const [tab, setTab] = useState<RunTab>(source ? "source" : "inputs");
    const inputsTitle: string = run.itemType === "tool" ? "Parameters" : "Inputs";
    const [valuesView, setValuesView] = useState<ValuesView>("list");
    const kind: string = run.itemType === "tool" ? "Tool" : "Action type";
    const problems: string[] = run.problems ?? [];
    return (
        <Modal isOpen variant="large" onClose={onClose} aria-labelledby="test-run-detail-title"
            elementToFocus="#test-run-detail-primary">
            <ModalHeader title={`Test run: ${run.itemName}`} labelId="test-run-detail-title" />
            <ModalBody>
                <dl className="axiom-test-run__meta">
                    <div>
                        <dt>Status</dt>
                        <dd><Label isCompact color={STATUS_COLOR[run.status]}>{run.status}</Label></dd>
                    </div>
                    <div>
                        <dt>{kind}</dt>
                        <dd>
                            <Button variant="link" isInline onClick={() => { onOpenItem(run.itemId); onClose(); }}>
                                {run.itemName}
                            </Button>
                        </dd>
                    </div>
                    <div>
                        <dt>Started</dt>
                        <dd>{new Date(run.startedAt).toLocaleString()}</dd>
                    </div>
                    <div>
                        <dt>By</dt>
                        <dd>{run.author === "ai" ? "AI" : "You"}</dd>
                    </div>
                    {run.durationMs !== undefined && run.status !== "suggested" && (
                        <div>
                            <dt>Duration</dt>
                            <dd>{run.durationMs} ms</dd>
                        </div>
                    )}
                    {run.exitCode !== undefined && (
                        <div>
                            <dt>Exit code</dt>
                            <dd>{run.exitCode}</dd>
                        </div>
                    )}
                </dl>

                {run.status === "suggested" && onRunSuggestion && (
                    <p className="axiom-text-subtle axiom-test-run__section">
                        The AI suggested this test. Choose Run… to review and edit the {run.itemType === "tool"
                            ? "parameters" : "inputs"} before running it.
                    </p>
                )}
                {run.message && <Alert isInline variant="warning" title={run.message} className="axiom-test-run__section" />}
                {problems.length > 0 && (
                    <Alert isInline variant="danger" title="Problems" className="axiom-test-run__section">
                        <ul>{problems.map((p, i) => <li key={`${i}-${p}`}>{p}</li>)}</ul>
                    </Alert>
                )}

                <Tabs className="axiom-test-run__tabs" activeKey={tab} onSelect={(_e, key) => setTab(key as RunTab)}
                    aria-label="Test run details" mountOnEnter>
                    {source && (
                        <Tab eventKey="source" title={<TabTitleText>{source.title}</TabTitleText>}>
                            <TabContentBody className="axiom-test-run__tab-body">
                                <CodeBlock code={run.resolvedScript ?? ""} language={source.language} label={source.title} />
                            </TabContentBody>
                        </Tab>
                    )}
                    <Tab eventKey="inputs" title={<TabTitleText>{inputsTitle}</TabTitleText>}>
                        <TabContentBody className="axiom-test-run__tab-body">
                            <ValuesPanel label={inputsTitle} values={run.inputs ?? {}} view={valuesView}
                                onViewChange={setValuesView}
                                emptyText={`No ${inputsTitle.toLowerCase()} were given.`} />
                        </TabContentBody>
                    </Tab>
                    {run.itemType !== "tool" && (
                        <Tab eventKey="outputs" title={<TabTitleText>Outputs</TabTitleText>}>
                            <TabContentBody className="axiom-test-run__tab-body">
                                {run.outputs
                                    ? <ValuesPanel label="Outputs" values={run.outputs} view={valuesView}
                                        onViewChange={setValuesView} emptyText="The action returned an empty object." />
                                    : <p className="axiom-text-subtle">
                                        No outputs (the action did not print a JSON object).
                                    </p>}
                            </TabContentBody>
                        </Tab>
                    )}
                    <Tab eventKey="streams" title={<TabTitleText>Stdout/err</TabTitleText>}>
                        <TabContentBody className="axiom-test-run__tab-body">
                            <StreamSection title="stdout" text={run.stdout} />
                            <StreamSection title="stderr" text={run.stderr} />
                        </TabContentBody>
                    </Tab>
                </Tabs>
            </ModalBody>
            <ModalFooter>
                {run.status === "suggested" && onRunSuggestion && (
                    <Button id="test-run-detail-primary" variant="primary"
                        onClick={() => { onClose(); onRunSuggestion(run); }}>Run…</Button>
                )}
                <Button id={run.status === "suggested" && onRunSuggestion ? undefined : "test-run-detail-primary"}
                    variant={run.status === "suggested" ? "link" : "primary"} onClick={onClose}>Close</Button>
            </ModalFooter>
        </Modal>
    );
}
