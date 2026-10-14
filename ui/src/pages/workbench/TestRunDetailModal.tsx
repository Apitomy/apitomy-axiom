import { useState } from "react";
import {
    Alert, Button, ExpandableSection, Label, Modal, ModalBody, ModalFooter, ModalHeader, Title,
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
type CodeLanguage = "json" | "bash" | "javascript" | "plaintext";

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
function CodeBlock({ code, language, label }: { code: string; language: CodeLanguage; label: string }) {
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

/** A section for text output: highlighted as JSON when it is JSON, otherwise plain text. */
function OutputSection({ title, text }: { title: string; text: string | undefined }) {
    if (!text) {
        return null;
    }
    const json: string | null = prettyJson(text);
    return (
        <section className="axiom-test-run__section">
            <Title headingLevel="h3" size="md">{title}</Title>
            <CodeBlock code={json ?? text} language={json ? "json" : "plaintext"} label={title} />
        </section>
    );
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
    const [scriptOpen, setScriptOpen] = useState<boolean>(false);
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

                <section className="axiom-test-run__section">
                    <Title headingLevel="h3" size="md">{run.itemType === "tool" ? "Parameters" : "Inputs"}</Title>
                    <CodeBlock code={JSON.stringify(run.inputs ?? {}, null, 2)} language="json"
                        label={run.itemType === "tool" ? "Parameters" : "Inputs"} />
                </section>
                {run.outputs && (
                    <section className="axiom-test-run__section">
                        <Title headingLevel="h3" size="md">Outputs</Title>
                        <CodeBlock code={JSON.stringify(run.outputs, null, 2)} language="json" label="Outputs" />
                    </section>
                )}
                <OutputSection title="stdout" text={run.stdout} />
                <OutputSection title="stderr" text={run.stderr} />
                {run.resolvedScript && (
                    <ExpandableSection className="axiom-test-run__section" toggleText="Resolved script"
                        isExpanded={scriptOpen} onToggle={(_e, open) => setScriptOpen(open)}>
                        <CodeBlock code={run.resolvedScript}
                            language={run.resolvedScript.startsWith("export ") ? "javascript" : "bash"}
                            label="Resolved script" />
                    </ExpandableSection>
                )}
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
